import subprocess, json, pathlib
root=pathlib.Path.cwd(); out=root/'reports/admin-workflow/2026-10-30-recheck'
common=json.loads((out/'docker-common.json').read_text())
def run(*args): return subprocess.check_output(['docker',*args],text=True)
for command,args in [('import-catalog',['--repaircalc.catalog.file=/app/data/catalog-v1.json','--repaircalc.catalog.mappings=/app/data/work-mappings-v1.json']),('import-rules',['--repaircalc.rules.file=/app/data/rules-v1.json'])]:
    (out/(command+'.log')).write_text(run('run','--rm',*common,'repaircalc-backend:local','--repaircalc.command='+command,*args))
pg=['-e','PGHOST=repaircalc-recheck-db','-e','PGDATABASE=repaircalc','-e','PGUSER=repaircalc','-e','PGPASSWORD=disposable-test-password']
result=run('run','--rm','--network','repaircalc-recheck',*pg,'-e','CLASSIFIER_QUEUE_TESTS=1','-v',str(root/'backend/classification/tests')+':/app/tests:ro','--entrypoint','python','repaircalc-classifier-workflow:local','-m','unittest','discover','-s','tests')
(out/'classifier-tests.log').write_text(result)
print(run('run','-d','--name','repaircalc-recheck-worker','--network','repaircalc-recheck',*pg,'-e','OLLAMA_URL=http://repaircalc-ollama-1:11434','-e','OLLAMA_MODEL=gemma4:12b','-e','OLLAMA_NUM_THREADS=8','-v',str(root/'backend/classification/repaircalc_classifier')+':/app/repaircalc_classifier:ro','--entrypoint','python','repaircalc-classifier-workflow:local','-m','repaircalc_classifier.worker'))
run('network','connect','repaircalc_default','repaircalc-recheck-worker')
