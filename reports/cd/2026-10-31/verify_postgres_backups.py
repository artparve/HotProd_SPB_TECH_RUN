"""Restore both rollback archives in an isolated, disposable PostgreSQL instance."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path('/opt/repaircalc/migration-20261031')
NAME = 'repaircalc-pg-backup-restore-check'


def main():
    os.umask(0o077)
    sys.path.insert(0, str(ROOT))
    import migrate_postgres as migration
    directory = Path((ROOT/'active-final-migration.txt').read_text().strip())
    state = json.loads((directory/'state.json').read_text())
    original = json.loads(subprocess.check_output(['docker','inspect',state['source_container']]))[0]
    env = dict(value.split('=',1) for value in original['Config']['Env'] if '=' in value)
    user, database = env['POSTGRES_USER'], env['POSTGRES_DB']
    launched = False
    reports = []
    sql = 'SELECT json_object_agg(table_name,row_count) FROM ('+' UNION ALL '.join(
        "SELECT '"+table+"' AS table_name,count(*) AS row_count FROM repaircalc."+table
        for table in migration.TABLES)+') counts;'
    try:
        subprocess.run(['docker','run','-d','--name',NAME,'--network','none','--cpus=1','--memory=512m',
                        '--label','repaircalc.migration.restore-check=true',
                        '--tmpfs','/var/lib/postgresql/data:rw,size=256m',
                        '-e','POSTGRES_USER='+user,'-e','POSTGRES_DB='+database,
                        '-e','POSTGRES_HOST_AUTH_METHOD=trust',original['Config']['Image']],
                        capture_output=True,check=True)
        launched = True
        for _ in range(60):
            result = subprocess.run(['docker','exec',NAME,'pg_isready','-U',user,'-d',database],capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Isolated PostgreSQL failed to start')
        for index, backup in enumerate(state['backups']):
            if index:
                subprocess.run(['docker','exec',NAME,'dropdb','-U',user,database],capture_output=True,check=True)
                subprocess.run(['docker','exec',NAME,'createdb','-U',user,database],capture_output=True,check=True)
            with Path(backup['file']).open('rb') as archive:
                result = subprocess.run(['docker','exec','-i',NAME,'pg_restore','--no-owner','--no-acl',
                                         '--exit-on-error','-U',user,'-d',database],stdin=archive,capture_output=True,timeout=120)
            log = directory/(backup['container']+'-restore.log')
            log.write_bytes(result.stdout+b'\n'+result.stderr)
            if result.returncode:
                raise RuntimeError('Archive restore failed; see private '+str(log))
            result = subprocess.run(['docker','exec','-i',NAME,'psql','-X','-qAt','-U',user,'-d',database],
                                    input=sql,text=True,capture_output=True,check=True)
            restored = json.loads(result.stdout)
            if backup['container'] == state['source_container']:
                expected = state['tables']
            else:
                result = subprocess.run(['docker','exec','-i',backup['container'],'sh','-c',
                    'exec psql -X -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                    input=sql,text=True,capture_output=True,check=True)
                expected = json.loads(result.stdout)
            assert restored == expected, 'Restored table counts differ'
            reports.append({'source_container':backup['container'],'archive':backup['file'],
                            'restore_verified':True,'table_counts':restored})
        path = directory/'postgres-restore-verification.json'
        path.write_text(json.dumps(reports,indent=2))
        print(json.dumps({'archives_restored':len(reports),'all_table_counts_equal':True,
                          'network':'none','production_data_modified':False,'private_report':str(path)}))
    finally:
        if launched:
            subprocess.run(['docker','rm','-f',NAME],capture_output=True,check=True)


if __name__ == '__main__':
    main()
