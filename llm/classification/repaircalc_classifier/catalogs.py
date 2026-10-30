"""Source adapters. No implicit join, deduplication or attribute overwrite."""
import json
import re
import xml.etree.ElementTree as ET
from html import unescape
from pathlib import Path


def clean(value):
    text = str(value or '')
    text = re.sub(r'<(?:br\b[^>]*|/p|/div)>', '\n', text, flags=re.I)
    text = re.sub(r'<[^>]+>', '', text)
    return unescape(text).strip()


def json_products(path):
    with Path(path).open(encoding='utf-8') as stream:
        products = json.load(stream)['products']
    for p in products:
        yield {
            'source': 'products_final.json', 'source_id': str(p['id']),
            'cod_good': str(p['cod_good']), 'name': clean(p['name']),
            'description': clean(p.get('description')),
            'marketing_description': clean(p.get('marketing_description')),
            'categories': [{'id': str(k), 'name': clean(v)} for k, v in p['categories'].items()],
            'attributes': [{'name': clean(a['name']), 'value': clean(a['value'])}
                           for a in p.get('characteristics', [])],
        }


def xml_products(path, category_sink=None):
    categories = {}
    # Remove completed offers from their container to bound XML memory.
    stack = []
    for event, element in ET.iterparse(path, events=('start', 'end')):
        if event == 'start':
            stack.append(element)
            continue
        if element.tag == 'category':
            categories[element.attrib['id']] = {
                'id': element.attrib['id'], 'parent_id': element.get('parentId'),
                'name': clean(element.text),
            }
        elif element.tag == 'offer':
            category_id = element.findtext('categoryId')
            chain, seen = [], set()
            while category_id:
                if category_id in seen:
                    raise ValueError('cycle in XML category hierarchy')
                seen.add(category_id)
                category = categories[category_id]
                chain.append({'id': category_id, 'name': category['name']})
                category_id = category['parent_id']
            url = element.findtext('url', '')
            match = re.search(r'/(\d+)/?$', url)
            yield {
                'source': 'export_yandex_spb.xml', 'source_id': element.attrib['id'],
                'cod_good': match.group(1) if match else None,
                'name': clean(element.findtext('name')),
                'description': clean(element.findtext('description')),
                'marketing_description': '', 'categories': list(reversed(chain)),
                'attributes': [{'name': clean(a.get('name')), 'value': clean(a.text),
                                'unit': a.get('unit')} for a in element.findall('param')],
            }
            stack[-2].remove(element)
            element.clear()
        stack.pop()
    if category_sink is not None:
        category_sink.update(categories)


def fields(product):
    """All classification text, with stable paths for verifiable evidence."""
    for key in ('name', 'description', 'marketing_description'):
        if product.get(key):
            yield key, product[key]
    for i, category in enumerate(product.get('categories', [])):
        yield f'categories[{i}].name', category['name']
    for i, attribute in enumerate(product.get('attributes', [])):
        yield f'attributes[{i}].value', attribute['value']
