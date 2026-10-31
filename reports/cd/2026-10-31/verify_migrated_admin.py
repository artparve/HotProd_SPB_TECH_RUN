"""Read migrated history through a temporary backend with ephemeral test authentication."""
import argparse
import base64
from decimal import Decimal
from collections import Counter
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.request import Request, build_opener, HTTPCookieProcessor
from urllib.error import HTTPError, URLError

ROOT = Path('/opt/repaircalc/migration-20261031')
sys.path.insert(0, str(ROOT))
import migrate_postgres as migration

NAME = 'repaircalc-migration-admin-check'
BASE = 'http://127.0.0.1:18001'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', default='repaircalc_prod_mongo-backend-1')
    parser.add_argument('--network', default='repaircalc_prod_mongo_default')
    parser.add_argument('--snapshot', type=Path, default=ROOT/'postgres-snapshot.jsonl')
    parser.add_argument('--report-dir', type=Path, default=ROOT)
    args = parser.parse_args()
    report_dir = args.report_dir
    os.umask(0o077)
    original = json.loads(subprocess.check_output(['docker', 'inspect', args.backend]))[0]
    env = dict(item.split('=', 1) for item in original['Config']['Env'] if '=' in item)
    password, salt = secrets.token_urlsafe(32), secrets.token_bytes(24)
    key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000, dklen=32)
    b64 = lambda value: base64.b64encode(value).decode()
    env.update(ADMIN_USERNAME='migration-verifier', ADMIN_PASSWORD_HASH=f'pbkdf2-sha256$600000${b64(salt)}${b64(key)}',
               ADMIN_COOKIE_SECURE='false', CLASSIFICATION_ENABLED='false')
    config = report_dir / 'temporary-admin-check.env'
    with config.open('x') as output:
        output.write(''.join(name+'='+value+'\n' for name, value in env.items()))
    report = {'temporary_authentication': True, 'production_authentication_changed': False}
    launched = False
    try:
        result = subprocess.run(['docker', 'run', '-d', '--name', NAME, '--network', args.network,
                                 '--read-only', '--cpus=1', '--memory=768m', '--cap-drop=ALL',
                                 '--security-opt=no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m',
                                 '--env-file', str(config), '-p', '127.0.0.1:18001:8000', original['Config']['Image']],
                                capture_output=True, text=True, check=True)
        launched = True
        config.unlink()
        opener = build_opener(HTTPCookieProcessor(CookieJar()))

        def request(path, body=None, csrf=None, authenticated=True):
            headers = {'Content-Type': 'application/json'}
            if csrf:
                headers['X-CSRF-Token'] = csrf
            req = Request(BASE+path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
            client = opener if authenticated else build_opener()
            try:
                with client.open(req, timeout=10) as response:
                    return response.status, json.load(response, parse_float=Decimal)
            except HTTPError as response:
                return response.code, json.load(response, parse_float=Decimal)

        for attempt in range(90):
            try:
                status, health = request('/actuator/health/readiness')
                if status == 200:
                    break
            except (URLError, OSError):
                pass
            time.sleep(1)
        else:
            raise AssertionError('Verification backend not ready')
        assert request('/api/v1/admin/products', authenticated=False)[0] == 401
        status, session = request('/api/v1/admin/session')
        assert status == 200
        status, session = request('/api/v1/admin/login', {'username': 'migration-verifier', 'password': password}, session['csrfToken'])
        assert status == 200 and session['authenticated'] is True
        source = migration.load_snapshot(args.snapshot.read_text())
        status, products = request('/api/v1/admin/products?size=100')
        assert status == 200 and products['total'] == len(source['admin_product'])
        assert {p['id'] for p in products['items']} == {p['id'] for p in source['admin_product']}
        expected_versions = {p['id']: p for p in source['admin_product_version']}
        cards, histories, jobs, reviews, readiness = [], [], [], [], []
        for product in source['admin_product']:
            product_id = product['id']
            path = '/api/v1/admin/products/'+product_id
            status, card = request(path)
            assert status == 200 and card['versionId'] == product['current_version_id']
            assert card['version'] == expected_versions[card['versionId']]['version']
            assert card['snapshot'] == expected_versions[card['versionId']]['snapshot']
            cards.append(card)
            status, history = request(path+'/history?size=100')
            expected = [p for p in source['admin_product_version'] if p['product_id'] == product_id]
            assert status == 200 and history['total'] == len(expected)
            assert {v['id'] for v in history['items']} == {v['id'] for v in expected}
            histories.extend(history['items'])
            status, classification = request(path+'/classifications')
            expected = [p for p in source['admin_classification_job'] if p['product_id'] == product_id]
            assert status == 200 and {v['id'] for v in classification} == {v['id'] for v in expected}
            for job in classification:
                original_job = next(row for row in expected if row['id'] == job['id'])
                assert job['status'] == original_job['status'] and job['attempts'] == original_job['attempts']
                assert job['result'] == original_job['result']
            jobs.extend(classification)
            status, history = request(path+'/reviews')
            expected = [p for p in source['admin_label_review'] if p['product_id'] == product_id]
            assert status == 200 and {v['id'] for v in history} == {v['id'] for v in expected}
            reviews.extend(history)
            status, state = request(path+'/readiness')
            assert status == 200
            readiness.append(state)
        report.update(status='verified', products=cards, versions=histories, classifications=jobs,
                      reviews=reviews, readiness=readiness, unauthenticated_rejected=True)
        path = report_dir/'admin-read-verification.json'
        path.write_text(migration.plain_json(report)); path.chmod(0o600)
        print(json.dumps({'status': 'verified', 'products': len(cards), 'versions': len(histories),
                          'classifications': len(jobs), 'reviews': len(reviews), 'unauthenticated_rejected': True,
                          'production_authentication_changed': False, 'private_report': str(path)}))
    finally:
        config.unlink(missing_ok=True)
        if launched:
            logs = subprocess.run(['docker', 'logs', '--tail', '60', NAME], capture_output=True, text=True)
            path = report_dir/'admin-verification-backend.log'
            path.write_text(logs.stdout+'\n'+logs.stderr); path.chmod(0o600)
            subprocess.run(['docker', 'rm', '-f', NAME], capture_output=True, check=True)


if __name__ == '__main__':
    main()
