"""Compare the live PostgreSQL and migrated Mongo applications without LLM calls."""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path('/opt/repaircalc/migration-20261031')
INPUT = {'area': 20, 'perimeter': 18, 'height': 2.7, 'reserve': 10, 'layers': 2, 'quantity': 6}


def request(port, path, body=None):
    req = Request(f'http://127.0.0.1:{port}{path}',
                  data=json.dumps(body).encode() if body is not None else None,
                  headers={'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except HTTPError as response:
        return response.code, json.load(response)


def normalize(product):
    result = dict(product)
    result['works'] = sorted(result['works'])
    # Additive metadata required by the new project calculator is checked
    # separately; all fields that existed in PostgreSQL must remain identical.
    result.pop('materialFamily', None)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before-port', type=int, default=8080)
    parser.add_argument('--after-port', type=int, default=8081)
    parser.add_argument('--report', type=Path, default=ROOT/'rehearsal-api-comparison.json')
    args = parser.parse_args()
    before_status, before = request(args.before_port, '/api/v1/products')
    after_status, after = request(args.after_port, '/api/v1/products')
    assert before_status == after_status == 200 and before and after, 'Catalog unavailable'
    old = {row['id']: normalize(row) for row in before}
    new = {row['id']: normalize(row) for row in after}
    assert old == new, 'Catalog content differs'
    assert all(p.get('materialFamily') in ('laminate', 'tile', 'vinyl') for p in after if p['kind'] == 'covering'), 'Missing covering family'
    checks = []
    for product_id in sorted(old):
        path = f'/api/v1/product/{product_id}/calc'
        a_status, a = request(args.before_port, path, INPUT)
        b_status, b = request(args.after_port, path, INPUT)
        record = {'product_id': product_id, 'before_status': a_status, 'after_status': b_status,
                  'before': a, 'after': b}
        if a_status == b_status == 200:
            # UUID/timestamp are generated for each calculation, not persisted business data.
            for result in (a, b):
                result.pop('id', None)
                result.pop('createdAt', None)
                for line in result['lines']:
                    line['product'] = normalize(line['product'])
            record['equal'] = a == b
        else:
            record['equal'] = a_status == b_status and a.get('code') == b.get('code')
        checks.append(record)
    report = {'catalog_equal': True, 'catalog_count': len(old), 'input': INPUT, 'calculations': checks,
              'all_calculations_equal': all(row['equal'] for row in checks)}
    path = args.report
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    path.chmod(0o600)
    print(json.dumps({'catalog_equal': True, 'catalog_count': len(old),
                      'calculations': len(checks), 'all_calculations_equal': report['all_calculations_equal'],
                      'mismatched_products': [row['product_id'] for row in checks if not row['equal']],
                      'private_report': str(path)}))
    return 0 if report['all_calculations_equal'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
