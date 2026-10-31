"""Explicit final copy into prod. Keeps PostgreSQL and ingress until CD verification."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path('/opt/repaircalc/migration-20261031')
CADDY = Path('/etc/caddy/sites/repaircalc.caddyfile')
BEGIN = '# BEGIN RepairCalc database migration write gate'
END = '# END RepairCalc database migration write gate'
WORKER = 'repaircalc-classifier-worker-1'
SOURCE = 'repaircalc-postgres-1'
TARGET = 'repaircalc_prod-mongo-1'


def main():
    os.umask(0o077)
    sys.path.insert(0, str(ROOT))
    import migrate_postgres as migration
    directory = ROOT / ('final-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    directory.mkdir()
    original = CADDY.read_text()
    if BEGIN in original or END in original:
        raise RuntimeError('An existing write gate requires inspection before another migration')
    worker = json.loads(subprocess.check_output(['docker', 'inspect', WORKER]))[0]
    was_running = worker['State']['Running']
    snapshot_original = directory/'caddy-before.caddyfile'
    snapshot_original.write_text(original)
    completed = False
    gate_installed = False

    def run(name, argv, input=None, timeout=300):
        result = subprocess.run(argv, input=input, text=True, capture_output=True, timeout=timeout)
        log = directory/(name+'.log')
        log.write_text(result.stdout+'\n'+result.stderr)
        if result.returncode:
            raise RuntimeError(name+' failed; see private '+str(log))
        return result.stdout

    try:
        match = re.search(r'(?m)^([ \t]*)handle(?:\s+@[^{}\n]+)?\s*\{', original)
        if not match:
            raise RuntimeError('Expected Caddy handle route not found')
        indent = match.group(1)
        block = '\n'.join(indent+line for line in (
            BEGIN, '@repaircalc_migration_writes {',
            '    path /api/v1/admin/* /api/v1/internal/*',
            '    method POST PUT PATCH DELETE', '}',
            'handle @repaircalc_migration_writes {',
            '    respond "Administrative writes paused during database migration" 503',
            '}', END, ''))
        if CADDY.read_text() != original:
            raise RuntimeError('Caddy configuration changed concurrently')
        CADDY.write_text(original[:match.start()]+block+original[match.start():])
        gate_installed = True
        run('caddy-validate', ['caddy', 'validate', '--config', '/etc/caddy/Caddyfile'])
        run('caddy-reload', ['systemctl', 'reload', 'caddy'])
        request = Request('http://127.0.0.1/api/v1/admin/products', data=b'{}',
                          headers={'Content-Type':'application/json', 'Host':'194.67.95.67'})
        try:
            with urlopen(request, timeout=10) as response:
                status = response.status
        except HTTPError as response:
            status = response.code
        if status != 503:
            raise RuntimeError('Administrative write gate was not verified: HTTP '+str(status))
        if was_running:
            run('stop-legacy-classifier', ['docker', 'stop', '-t', '30', WORKER])
        print(json.dumps({'phase':'source_write_gate_verified','status':status,'public_reads_remain_available':True}), flush=True)
        summary = migration.export_snapshot(SOURCE, directory/'postgres-snapshot.jsonl')
        tables = migration.load_snapshot((directory/'postgres-snapshot.jsonl').read_text())
        reference_rules = ROOT/'source/data/rules-v1.json'
        reference_catalog = ROOT/'source/data/catalog-v1.json'
        collections = migration.transform(tables, reference_rules, reference_catalog)
        prepared = directory/'prepared.json'
        payload = {'sourceSha256':summary['sha256'],
                   'referenceRulesSha256':hashlib.sha256(reference_rules.read_bytes()).hexdigest(),
                   'referenceCatalogSha256':hashlib.sha256(reference_catalog.read_bytes()).hexdigest(),
                   'collections':collections}
        migration.private_write(prepared, json.dumps(payload, ensure_ascii=False))
        backups = []
        for container in (SOURCE, 'repaircalc_prod-postgres-1'):
            archive = directory/(container+'.dump')
            with archive.open('xb') as output:
                result = subprocess.run(['docker','exec',container,'sh','-c',
                    'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], stdout=output, stderr=subprocess.PIPE, timeout=180)
            if result.returncode:
                raise RuntimeError('PostgreSQL backup failed')
            with archive.open('rb') as source:
                result = subprocess.run(['docker','exec','-i',container,'pg_restore','--list'],
                                        stdin=source, capture_output=True, timeout=60)
            if result.returncode:
                raise RuntimeError('PostgreSQL backup directory validation failed')
            backups.append({'container':container,'file':str(archive),'bytes':archive.stat().st_size,
                            'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'toc_valid':True})
        print(json.dumps({'phase':'final_snapshot_and_backups_verified','tables':summary['tables']}), flush=True)
        imported = run('mongo-import', [sys.executable,str(ROOT/'migrate_postgres.py'),'import',
                       '--container',TARGET,'--prepared',str(prepared),'--database','repaircalc'])
        result = json.loads(imported)
        if result.get('status') != 'verified':
            raise RuntimeError('Mongo import was not verified')
        state = {'phase':'import_verified_waiting_for_prod_CD','source_container':SOURCE,'target_container':TARGET,
                 'project':'repaircalc_prod','database':'repaircalc','sourceSha256':summary['sha256'],
                 'tables':summary['tables'],'counts':result['counts'],'backups':backups,
                 'caddy_backup':str(snapshot_original),'worker_was_running':was_running,
                 'source_write_gate_active':True,'postgres_removed':False,'ingress_switched':False}
        (directory/'state.json').write_text(json.dumps(state,indent=2))
        (ROOT/'active-final-migration.txt').write_text(str(directory)+'\n')
        completed = True
        print(json.dumps({'phase':state['phase'],'directory':str(directory),'counts':result['counts'],
                          'source_write_gate_active':True,'postgres_removed':False}), flush=True)
    finally:
        if not completed:
            if gate_installed:
                CADDY.write_text(original)
                subprocess.run(['systemctl','reload','caddy'],capture_output=True)
            if was_running:
                subprocess.run(['docker','start',WORKER],capture_output=True)
            print(json.dumps({'phase':'migration_failed_source_restored','postgres_removed':False}), flush=True)


if __name__ == '__main__':
    main()
