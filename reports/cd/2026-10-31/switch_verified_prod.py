"""Operator cutover after successful CD; refuses source drift and retains rollback."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.request import Request, urlopen

ROOT = Path('/opt/repaircalc/migration-20261031')

def main():
    os.umask(0o077)
    sys.path.insert(0, str(ROOT))
    import migrate_postgres as migration
    directory = Path((ROOT/'active-final-migration.txt').read_text().strip())
    state = json.loads((directory/'state.json').read_text())
    if state.get('ingress_switched'):
        raise RuntimeError('Cutover already recorded')
    current = Path('/opt/repaircalc/prod/current').resolve()
    if current.name != 'sha-f4c73472c46aef9a547b81dbf56171087a46d9da-20261031T172553Z-94560833':
        raise RuntimeError('Expected successful new CD release pointer; inspect current before proceeding')
    fresh = directory/'source-before-cutover.jsonl'
    migration.export_snapshot('repaircalc-postgres-1', fresh)
    def canonical(path):
        tables = migration.load_snapshot(path.read_text())
        return {name: sorted(json.dumps(migration.extended(row), sort_keys=True) for row in rows)
                for name, rows in tables.items()}
    if canonical(fresh) != canonical(directory/'postgres-snapshot.jsonl'):
        raise RuntimeError('Source changed after migration; refusing cutover')
    subprocess.run([sys.executable, str(ROOT/'verify_migration.py'), '--report',
                    str(directory/'post-CD-api-comparison.json')], check=True)
    caddy = Path('/etc/caddy/sites/repaircalc.caddyfile')
    original = caddy.read_text()
    pattern = r'(?m)^[ \t]*# BEGIN RepairCalc database migration write gate\n.*?^[ \t]*# END RepairCalc database migration write gate\n'
    updated, gates = re.subn(pattern, '', original, flags=re.S)
    if gates != 1 or updated.count('reverse_proxy 127.0.0.1:8080') != 1:
        raise RuntimeError('Unexpected Caddy routes')
    updated = updated.replace('reverse_proxy 127.0.0.1:8080', 'reverse_proxy 127.0.0.1:8081')
    migration.private_write(directory/'caddy-before-cutover.caddyfile', original)
    try:
        caddy.write_text(updated)
        subprocess.run(['caddy','validate','--config','/etc/caddy/Caddyfile'], check=True, capture_output=True)
        subprocess.run(['systemctl','reload','caddy'], check=True)
        for path in ('/api/v1/products', '/'):
            with urlopen(Request('http://127.0.0.1'+path, headers={'Host':'194.67.95.67'}), timeout=20) as response:
                assert response.status == 200
                if path.endswith('products'):
                    assert len(json.load(response)) == 13
    except BaseException:
        caddy.write_text(original)
        subprocess.run(['systemctl','reload','caddy'], check=True)
        raise
    state.update(phase='ingress_switched_pending_browser', ingress_switched=True,
                 source_write_gate_active=False, cd_release=str(current))
    (directory/'state.json').write_text(json.dumps(state, indent=2))
    print(json.dumps({'source_unchanged':True, 'ingress_port':8081, 'cd_release':current.name,
                      'postgres_removed':False}))

if __name__ == '__main__':
    main()
