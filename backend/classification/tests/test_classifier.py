import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from langchain_core.runnables import RunnableLambda
from langchain_core.messages import AIMessage
from pydantic import ValidationError

from repaircalc_classifier.catalogs import clean, json_products, xml_products
from repaircalc_classifier.classifier import build_chain, classify
from repaircalc_classifier.schema import Classification, Product, TAXONOMY, validate_evidence


def product():
    return Product(source='test', source_id='1', name='Краска для стен и потолков',
                   attributes=[{'name': 'Влагостойкость', 'value': 'нет'},
                               {'name': 'Тип поверхности', 'value': 'бетон'}])


def answer():
    return {
        'scope': 'in_scope',
        'work_types': [{'label': 'paint', 'evidence': [{'field': 'name', 'quote': 'Краска'}]}],
        'surface_locations': [{'label': 'wall', 'evidence': [{'field': 'name', 'quote': 'стен'}]},
                              {'label': 'ceiling', 'evidence': [{'field': 'name', 'quote': 'потолков'}]}],
        'substrates': [{'label': 'concrete', 'evidence': [
            {'field': 'attributes[1].value', 'quote': 'бетон'}]}],
        'properties': {e['id']: {'state': 'unknown', 'evidence': []} for e in TAXONOMY['properties']},
        'needs_review': False, 'review_reasons': [],
    }


class SchemaTests(unittest.TestCase):
    def test_positive_and_negative_property(self):
        result = answer()
        result['properties']['moisture_resistant'] = {
            'state': 'no', 'evidence': [{'field': 'attributes[0].value', 'quote': 'нет'}]}
        validate_evidence(Classification.model_validate(result), product())

    def test_reject_unknown_label_and_extra_keys(self):
        for mutate in (lambda a: a['work_types'][0].update(label='invented'),
                       lambda a: a.update(confidence=0.99)):
            result = answer()
            mutate(result)
            with self.assertRaises(ValidationError):
                Classification.model_validate(result)

    def test_reject_fabricated_quote_and_field(self):
        for field, quote in [('name', 'для ванной'), ('attributes[8].value', 'бетон')]:
            result = answer()
            result['substrates'][0]['evidence'] = [{'field': field, 'quote': quote}]
            with self.assertRaises(ValueError):
                validate_evidence(Classification.model_validate(result), product())

    def test_duplicate_labels(self):
        result = answer()
        result['work_types'] *= 2
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)

    def test_conflict_requires_review_and_two_quotes(self):
        result = answer()
        prop = {'state': 'conflict', 'evidence': [{'field': 'name', 'quote': 'Краска'}]}
        result['properties']['moisture_resistant'] = prop
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)
        prop['evidence'].append({'field': 'attributes[0].value', 'quote': 'нет'})
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)
        result.update(needs_review=True, review_reasons=['Противоречие в исходных данных'])
        Classification.model_validate(result)

    def test_out_of_scope_empty_labels(self):
        result = answer()
        result['scope'] = 'out_of_scope'
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)
        result.update(work_types=[], surface_locations=[], substrates=[])
        Classification.model_validate(result)

    def test_unknown_property_and_uncertainty(self):
        result = answer()
        result['properties']['moisture_resistant']['evidence'] = [{'field': 'name', 'quote': 'Краска'}]
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)
        result = answer()
        result['scope'] = 'uncertain'
        with self.assertRaises(ValidationError):
            Classification.model_validate(result)

    def test_parsing_failure_is_not_empty_success(self):
        chain = RunnableLambda(lambda _: {'parsed': None, 'parsing_error': 'invalid'})
        with self.assertRaises(ValueError):
            classify(product(), chain, model='test', base_url='http://localhost')

    def test_truncated_response_is_error(self):
        chain = RunnableLambda(lambda _: {
            'parsed': Classification.model_validate(answer()), 'parsing_error': None,
            'raw': AIMessage(content='', response_metadata={'done_reason': 'length'}),
        })
        with self.assertRaisesRegex(ValueError, 'truncated'):
            classify(product(), chain, model='test', base_url='http://localhost')

    def test_saved_schema_matches_code(self):
        path = Path(__file__).parents[1] / 'repaircalc_classifier/output.schema.json'
        self.assertEqual(json.loads(path.read_text(encoding='utf-8')), Classification.model_json_schema())

    def test_langchain_ollama_round_trip_mock(self):
        # Real LangChain prompt -> ChatOllama -> native schema -> Pydantic -> evidence validator.
        # Only the Ollama client's transport is replaced; no claim about model quality.
        content = json.dumps(answer(), ensure_ascii=False)
        def chat(**kwargs):
            self.assertIsInstance(kwargs['format'], dict)
            self.assertIn('work_types', kwargs['format']['properties'])
            self.assertIn('paint', json.dumps(kwargs['format']))
            self.assertEqual(kwargs['options']['temperature'], 0)
            self.assertIs(kwargs['think'], False)
            self.assertEqual(kwargs['messages'][0]['role'], 'system')
            self.assertIn('недоверенные данные', kwargs['messages'][0]['content'])
            self.assertIn('evidence_fields', kwargs['messages'][1]['content'])
            self.assertEqual(kwargs['options']['num_thread'], 4)
            return iter([{'model': 'test', 'message': {'role': 'assistant', 'content': content},
                          'done': True, 'done_reason': 'stop', 'eval_count': 1}])
        with patch('ollama.Client.chat', side_effect=chat) as mocked:
            result = classify(product(), build_chain('test', num_threads=4), model='test', base_url='http://localhost')
        self.assertEqual(mocked.call_count, 1)
        self.assertEqual(result['classification']['work_types'][0]['label'], 'paint')
        self.assertEqual(len(result['input_sha256']), 64)


class AdapterTests(unittest.TestCase):
    def test_html_and_duplicate_attributes(self):
        self.assertEqual(clean('бетон<br>стены &amp; потолок'), 'бетон\nстены & потолок')
        raw = {'products': [{'id': 1, 'cod_good': '100', 'name': 'x', 'categories': {'1': 'C'},
                            'characteristics': [{'name': 'Влагостойкость', 'value': 'да'},
                                                {'name': 'Влагостойкость', 'value': 'нет'}]}]}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'catalog.json'
            path.write_text(json.dumps(raw), encoding='utf-8')
            items = list(json_products(path))
        self.assertEqual([a['value'] for a in items[0]['attributes']], ['да', 'нет'])

    def test_xml_identifiers_hierarchy_and_units(self):
        xml = '''<yml_catalog><shop><categories><category id="1">Root</category>
        <category id="2" parentId="1">Leaf</category></categories><offers>
        <offer id="different"><url>https://example.test/item/123/</url><categoryId>2</categoryId>
        <name>Клей</name><param name="Вес" unit="кг">5</param></offer></offers></shop></yml_catalog>'''
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'catalog.xml'
            path.write_text(xml, encoding='utf-8')
            items = list(xml_products(path))
        self.assertEqual(items[0]['source_id'], 'different')
        self.assertEqual(items[0]['cod_good'], '123')
        self.assertEqual([c['name'] for c in items[0]['categories']], ['Root', 'Leaf'])
        self.assertEqual(items[0]['attributes'][0]['unit'], 'кг')


if __name__ == '__main__':
    unittest.main()
