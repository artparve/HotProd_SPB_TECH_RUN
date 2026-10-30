"""Disposable local acceptance environment; uses only public test credentials."""
import subprocess, pathlib, time, json
root = pathlib.Path.cwd()
out = root / 'reports/admin-workflow/2026-10-30-recheck'
def run(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()
run('cp', 'repaircalc-chain-recheck:/build/target/repaircalc-backend-0.1.0-SNAPSHOT.jar', str(out/'backend.jar'))
run('network', 'create', 'repaircalc-recheck')
run('run','-d','--name','repaircalc-recheck-db','--network','repaircalc-recheck','-e','POSTGRES_DB=repaircalc','-e','POSTGRES_USER=repaircalc','-e','POSTGRES_PASSWORD=disposable-test-password','postgres:17.11')
for _ in range(30):
    try:
        run('exec','repaircalc-recheck-db','pg_isready','-U','repaircalc'); break
    except subprocess.CalledProcessError: time.sleep(1)
common=['--network','repaircalc-recheck','--cpus=2','--memory=1536m','-e','JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 -Xmx512m','-e','DB_URL=jdbc:postgresql://repaircalc-recheck-db:5432/repaircalc','-e','DB_USER=repaircalc','-e','DB_PASSWORD=disposable-test-password','-e','SERVER_ADDRESS=0.0.0.0','-v',str(out/'backend.jar')+':/app/backend.jar:ro','-v',str(root/'backend/data')+':/app/data:ro']
run('run','-d','--name','repaircalc-recheck-api',*common,'-p','127.0.0.1:18082:8000','-e','ADMIN_COOKIE_SECURE=false','-e','ADMIN_USERNAME=catalog-admin','-e','ADMIN_PASSWORD_HASH=pbkdf2-sha256$600000$cmVwYWlyY2FsYy10ZXN0LXNhbHQtMjAyNg==$gjyWxpevd0JL54Z/gOniKJcBX3zkrSyCsUk8Bhd5uqY=','repaircalc-backend:local')
(out/'docker-common.json').write_text(json.dumps(common))
print('Disposable API starting on 18082')
