"""Reproducible full catalog inventory and lexical support for a proposed taxonomy."""
import argparse
import collections
import csv
import hashlib
import json
import re
from pathlib import Path

from .catalogs import fields, json_products, xml_products

HERE = Path(__file__).parent


def fingerprint(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-catalog', type=Path, required=True)
    parser.add_argument('--xml-catalog', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    taxonomy = json.loads((HERE / 'taxonomy.json').read_text(encoding='utf-8'))
    groups = ('work_types', 'surface_locations', 'substrates', 'properties')
    patterns = [(g, e['id'], re.compile(e['candidate_pattern'], re.I))
                for g in groups for e in taxonomy[g]]
    support = {g: {e['id']: {'counts': {}, 'examples': []} for e in taxonomy[g]} for g in groups}
    summary, category_rows, attr_rows, examples = {}, [], [], []
    xml_categories = {}
    for source, path, products in [
        ('json', args.json_catalog, json_products(args.json_catalog)),
        ('xml', args.xml_catalog, xml_products(args.xml_catalog, xml_categories)),
    ]:
        count, category_counts, attr_counts = 0, collections.Counter(), collections.Counter()
        names, roots, values = {}, collections.Counter(), collections.defaultdict(collections.Counter)
        source_support = collections.Counter()
        for product in products:
            count += 1
            category = product['categories'][-1]
            category_counts[category['id']] += 1
            names[category['id']] = category['name']
            if source == 'xml':
                roots[product['categories'][0]['name']] += 1
            for name in {a['name'] for a in product['attributes']}:
                attr_counts[name] += 1
            for a in product['attributes']:
                if a['name'] in ('Тип поверхности', 'Влагостойкость', 'Влагостойкость обоев', 'Применение', 'Назначение'):
                    values[a['name']][a['value']] += 1
            all_fields = list(fields(product))
            category_fields = [(p, v) for p, v in all_fields if p.startswith('categories')]
            # Broad lexical candidates only, including possible negations and unrelated senses.
            for group, label, pattern in patterns:
                haystack = category_fields if group == 'work_types' else all_fields
                hit = next(((p, v) for p, v in haystack if pattern.search(v)), None)
                if hit:
                    source_support[(group, label)] += 1
                    bucket = support[group][label]['examples']
                    if sum(e['source'] == source for e in bucket) < 2:
                        p, v = hit
                        bucket.append({'source': source, 'source_id': product['source_id'],
                                       'cod_good': product['cod_good'], 'name': product['name'],
                                       'field': p, 'quote': v[:1000]})
            if source == 'json' and len(examples) < 12 and re.search(
                r'краска.*стен|грунтовка|розетка|кабель|линолеум|шпатлевка|обои|щепа для копчения|плитка.*настенн|герметик',
                product['name'], re.I,
            ) and not any(e['categories'] == product['categories'] for e in examples):
                examples.append(product)
        for g, label, _ in patterns:
            support[g][label]['counts'][source] = source_support[(g, label)]
        summary[source] = {'file': path.name, 'sha256': fingerprint(path), 'products': count,
                           'used_leaf_category_ids': len(category_counts), 'roots': dict(roots),
                           'attribute_values': {k: v.most_common(30) for k, v in values.items()}}
        for cid, n in category_counts.items():
            category_rows.append([source, cid, names[cid], n])
        attr_rows.extend([source, k, n] for k, n in attr_counts.most_common())
    summary['xml']['all_category_ids'] = len(xml_categories)
    summary['taxonomy_version'] = taxonomy['version']
    summary['method'] = 'Full scan; lexical support is NOT product classification, ground truth or accuracy. Sources overlap; counts must not be added.'
    write_json(args.output / 'dataset_summary.json', summary)
    write_json(args.output / 'taxonomy_support.json', support)
    write_json(args.output / 'xml_categories.json', list(xml_categories.values()))
    for filename, header, rows in [
        ('source_categories.tsv', ['source', 'category_id', 'name', 'products'], category_rows),
        ('source_attributes.tsv', ['source', 'name', 'products_with_attribute'], attr_rows),
    ]:
        with (args.output / filename).open('w', encoding='utf-8', newline='') as stream:
            writer = csv.writer(stream, delimiter='\t')
            writer.writerow(header)
            writer.writerows(sorted(rows))
    write_json(args.output / 'sample_products.json', examples)
    print(json.dumps({k: {'products': v['products'], 'categories': v['used_leaf_category_ids']}
                      for k, v in summary.items() if isinstance(v, dict)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
