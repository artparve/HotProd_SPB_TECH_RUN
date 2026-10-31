"""Retire the two explicitly migrated PostgreSQL containers; never delete volumes."""
import json
import os
from pathlib import Path
import subprocess
from urllib.request import Request, urlopen

ROOT = Path('/opt/repaircalc/migration-20261031')
PG = {'repaircalc-postgres-1':'repaircalc_pgdata', 'repaircalc_prod-postgres-1':'repaircalc_prod_pgdata'}

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def main():
    os.umask(0o077)
    directory = Path((ROOT/'active-final-migration.txt').read_text().strip())
    state = json.loads((directory/'state.json').read_text())
    assert state.get('ingress_switched') and not state.get('postgres_removed')
    assert (directory/'postgres-restore-verification.json').is_file()
    inspect = json.loads(run('docker','inspect',*PG))
    for container in inspect:
        name = container['Name'].lstrip('/')
        assert any(m.get('Name') == PG[name] and m['Type'] == 'volume' and
                   m['Destination'] == '/var/lib/postgresql/data' for m in container['Mounts'])
        assert (directory/(name+'.dump')).stat().st_size > 0
    backup = directory/'retired-postgres-inspect.json'
    with backup.open('x') as stream:
        json.dump(inspect, stream)
    dependencies = ['repaircalc-classifier-worker-1', 'repaircalc-frontend-1',
                    'repaircalc-estimator-1', 'repaircalc-backend-1', 'repaircalc_prod-estimator-1']
    run('docker','stop','-t','30',*dependencies)
    run('docker','stop','-t','30',*PG)
    run('docker','rm',*PG)
    volumes = json.loads(run('docker','volume','inspect',*PG.values()))
    assert {v['Name'] for v in volumes} == set(PG.values())
    with urlopen(Request('http://127.0.0.1/api/v1/products', headers={'Host':'194.67.95.67'}), timeout=20) as response:
        assert len(json.load(response)) == 13
    state.update(phase='complete', postgres_removed=True, preserved_postgres_volumes=list(PG.values()),
                 public_browser_estimate_total=79175)
    (directory/'state.json').write_text(json.dumps(state, indent=2))
    print(json.dumps({'postgres_containers_removed':list(PG), 'volumes_preserved':list(PG.values()),
                      'backups_preserved':True, 'public_catalog_count':13}))

if __name__ == '__main__':
    main()
