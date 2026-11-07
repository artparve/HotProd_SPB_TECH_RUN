#!/usr/bin/env python3
"""Run on the user-authorized Ollama host with a staged JAR, sources and research index.

Creates only temporary probe containers and a temporary network. Never uses the production DB.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import time
import uuid


def run(argv, phase, timeout=180):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise RuntimeError(phase + " timed out") from None
    if result.returncode:
        # Never include argv: the probe token is passed to docker in memory.
        raise RuntimeError(phase + " failed: " + result.stderr[-2500:])
    return result.stdout


def state(name):
    d = json.loads(run(['docker', 'inspect', name], 'inspect'))[0]
    return {'name': name, 'id': d['Id'], 'image': d['Config']['Image'], 'startedAt': d['State']['StartedAt'],
            'status': d['State']['Status'], 'health': d['State'].get('Health', {}).get('Status')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--local', action='store_true', help='Use existing local Ollama and local cached images')
    args = parser.parse_args()
    root = args.directory.resolve()
    if not args.local and not str(root).startswith('/tmp/repaircalc-rag-'):
        raise ValueError('Expected isolated /tmp/repaircalc-rag-* staging directory')
    output = root / 'output'
    output.mkdir(exist_ok=True)
    names = ['repaircalc_prod-backend-1', 'repaircalc_prod-llm-1', 'repaircalc_prod-mongo-1',
             'repaircalc_prod-frontend-1', 'repaircalc_prod-ollama-1']
    if args.local:
        names = ['repaircalc-ollama-1']
    before = [state(n) for n in names]
    if args.local:
        images = {'repaircalc_prod-backend-1': 'repaircalc-backend:local',
                  'repaircalc_prod-llm-1': 'repaircalc-estimator:room-plan-v1',
                  'repaircalc_prod-mongo-1': 'mongo:7.0'}
    else:
        images = {d['name']: d['image'] for d in before}
    ollama = 'repaircalc-ollama-1' if args.local else 'repaircalc_prod-ollama-1'
    ollama_info = json.loads(run(['docker', 'inspect', ollama], 'Ollama network'))[0]
    ollama_network = next(iter(ollama_info['NetworkSettings']['Networks']))
    inference_timeout = 600 if args.local else 60
    prefix = 'repaircalc-rag-probe-' + uuid.uuid4().hex[:8]
    mongo, gateway, backend = [prefix + '-' + s for s in ['mongo', 'llm', 'backend']]
    created = []
    network_created = False
    token = secrets.token_hex(32)
    report = {'status': 'failed', 'mode': 'local' if args.local else 'remote', 'prefix': prefix, 'productionBefore': before,
              'jarSha256': hashlib.sha256((root / 'backend.jar').read_bytes()).hexdigest(),
              'indexId': json.loads((root / 'reference-index/manifest.json').read_text())['indexId']}
    def start(name, arguments):
        run(['docker', 'run', '-d', '--name', name] + arguments, 'start ' + name)
        created.append(name)
    def wait_until(check, phase, attempts=60):
        last_error = ''
        for _ in range(attempts):
            try:
                check()
                return
            except (RuntimeError, subprocess.TimeoutExpired) as failure:
                last_error = str(failure)[-1500:]
                time.sleep(1)
        raise RuntimeError(phase + ' timed out: ' + last_error)
    volume = ['-v', str(root) + ':/probe:ro']
    env = {'SERVER_ADDRESS': '0.0.0.0', 'SPRING_PROFILES_ACTIVE': 'deploy', 'MONGO_URI': f'mongodb://{mongo}:27017/rag_probe?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=5000',
           'CLASSIFICATION_TAXONOMY': '/probe/data/classification/taxonomy.json',
           'INSPIRATIONS_DIR': '/probe/data/inspirations', 'INTERNAL_SERVICE_TOKEN': token,
           'LLM_SERVICE_URL': f'http://{gateway}:8010', 'LLM_REQUEST_TIMEOUT_SECONDS': str(inference_timeout + 10),
           'RAG_REFERENCE_TIMEOUT_SECONDS': str(inference_timeout),
           'RAG_REFERENCE_SEARCH_ENABLED': 'true', 'RAG_REFERENCE_INDEX': '/probe/reference-index',
           'JAVA_TOOL_OPTIONS': '-XX:ActiveProcessorCount=2 -Xmx512m'}
    env_args = [part for key, value in env.items() for part in ['-e', key + '=' + value]]
    try:
        print('Creating isolated test database and gateway', flush=True)
        run(['docker', 'network', 'create', prefix], 'create probe network')
        network_created = True
        start(mongo, ['--network', prefix, '--memory', '512m', '--cpus', '1',
                      '--tmpfs', '/data/db:rw,nosuid,size=512m', images['repaircalc_prod-mongo-1'],
                      'mongod', '--bind_ip_all', '--replSet', 'rs0', '--wiredTigerCacheSizeGB', '0.25', '--oplogSize', '32'])
        wait_until(lambda: run(['docker', 'exec', mongo, 'mongosh', 'mongodb://127.0.0.1:27017/admin?directConnection=true&serverSelectionTimeoutMS=2000', '--quiet', '--eval', 'db.runCommand({ping:1})'], 'mongo ping', 8), 'mongo startup')
        config = {'_id': 'rs0', 'members': [{'_id': 0, 'host': mongo + ':27017'}]}
        run(['docker', 'exec', mongo, 'mongosh', 'mongodb://127.0.0.1:27017/admin?directConnection=true&serverSelectionTimeoutMS=2000', '--quiet', '--eval', 'rs.initiate(' + json.dumps(config) + ')'], 'mongo replica initialization')
        wait_until(lambda: run(['docker', 'exec', mongo, 'mongosh', 'mongodb://127.0.0.1:27017/admin?directConnection=true&serverSelectionTimeoutMS=2000', '--quiet', '--eval', 'if(!db.hello().isWritablePrimary)quit(2)'], 'mongo primary', 8), 'mongo election')
        for command, files in [('import-catalog', ['--repaircalc.catalog.file=/probe/data/catalog-v1.json', '--repaircalc.catalog.mappings=/probe/data/work-mappings-v1.json']),
                               ('import-rules', ['--repaircalc.rules.file=/probe/data/rules-v1.json'])]:
            print(command + ' into the isolated database', flush=True)
            logs = run(['docker', 'run', '--rm', '--user', '0:0', '--network', prefix, '--memory', '768m', '--cpus', '2',
                        '--entrypoint', 'java'] + volume + env_args + [images['repaircalc_prod-backend-1'], '-jar', '/probe/backend.jar',
                        '--repaircalc.command=' + command] + files, command)
            (output / (command + '.log')).write_text(logs)
        gateway_env = {'PYTHONPATH': '/probe/llm:/probe/llm/estimation:/probe/llm/classification', 'DATA_DIR': '/app/data',
                       'LLM_ADDRESS': '0.0.0.0', 'LLM_PORT': '8010', 'INTERNAL_SERVICE_TOKEN': token,
                       'OLLAMA_URL': 'http://' + ollama + ':11434', 'OLLAMA_MODEL': 'gemma4:12b',
                       'OLLAMA_TIMEOUT_SECONDS': str(inference_timeout + 10), 'RAG_REFERENCE_TIMEOUT_SECONDS': str(inference_timeout),
                       'OLLAMA_CONTEXT_LENGTH': '8192' if args.local else '16384', 'OLLAMA_NUM_PREDICT': '1024'}
        gateway_args = [part for key, value in gateway_env.items() for part in ['-e', key + '=' + value]]
        start(gateway, ['--user', '0:0', '-w', '/probe', '--network', ollama_network, '--memory', '512m', '--cpus', '2',
                        '--entrypoint', 'python'] + volume + gateway_args + [images['repaircalc_prod-llm-1'], '-m', 'repaircalc_llm'])
        run(['docker', 'network', 'connect', prefix, gateway], 'connect gateway to isolated network')
        start(backend, ['--user', '0:0', '--network', prefix, '--memory', '768m', '--cpus', '2', '--entrypoint', 'java']
              + volume + env_args + [images['repaircalc_prod-backend-1'], '-jar', '/probe/backend.jar'])
        health = f'import urllib.request; urllib.request.urlopen("http://{backend}:8000/actuator/health/readiness",timeout=3).read()'
        wait_until(lambda: run(['docker', 'exec', gateway, 'python', '-c', health], 'backend readiness', 8), 'backend readiness')
        request = {'works': ['floor'], 'conditions': {'humidity': 'normal', 'load': 'normal', 'heated': True},
                   'query': 'Quick-Step: какие условия и ограничения описаны для пола с подогревом?',
                   'explain': True, 'referenceSearch': True}
        code = '''import json,urllib.request,time
body=BODY
request=urllib.request.Request(URL,data=json.dumps(body,ensure_ascii=False).encode(),headers={"Content-Type":"application/json"})
start=time.monotonic()
with urllib.request.urlopen(request,timeout=TIMEOUT) as response: data=json.load(response)
print(json.dumps({"elapsedSeconds":time.monotonic()-start,"response":data},ensure_ascii=False))
'''.replace('BODY', repr(request)).replace('URL', repr(f'http://{backend}:8000/api/v1/material-recommendations')).replace('TIMEOUT', str(inference_timeout + 20))
        print('Running real inference: public Java endpoint -> gateway -> Ollama', flush=True)
        result = json.loads(run(['docker', 'exec', gateway, 'python', '-c', code], 'real RAG inference', inference_timeout + 30))
        (output / 'response.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        response = result['response']
        references = response['references']
        assert references['status'] == 'unverified_model_text', references.get('fallbackReason')
        assert references['model'] == 'gemma4:12b'
        assert references['usableForDecisions'] is False
        assert references['indexId'] == report['indexId']
        allowed = {f['chunkId'] for f in references['fragments']}
        assert any(f['sourceId'] == 'quickstep-heating' for f in references['fragments'])
        assert references['items'] and all(set(i['chunkIds']) <= allowed for i in references['items'])
        assert all(c['compatibility'] == 'unknown' and c['calculationReadiness'] == 'unavailable'
                   for category in response['categories'] for c in category['candidates'])
        assert response.get('knowledgeReleaseId') is None
        report.update(status='ok', model=references['model'], elapsedSeconds=result['elapsedSeconds'],
                      requestId=response['requestId'], referenceStatus=references['status'],
                      returnedFragments=len(allowed), generatedItems=len(references['items']), confirmedSkuCount=0)
        runtime = '''import json,urllib.request
with urllib.request.urlopen("http://OLLAMA_CONTAINER:11434/api/ps",timeout=10) as response: data=json.load(response)
print(json.dumps([{k:m.get(k) for k in ["name","digest","size","size_vram","context_length"]} for m in data.get("models",[])]))
'''
        report['ollamaRuntime'] = json.loads(run(['docker', 'exec', gateway, 'python', '-c', runtime.replace('OLLAMA_CONTAINER', ollama)], 'Ollama runtime'))
    except Exception as failure:
        report['errorType'] = type(failure).__name__
        report['error'] = str(failure)[:3000]
        for name in created:
            (output / (name.rsplit('-', 1)[-1] + '.log')).write_text(run(['docker', 'logs', '--tail', '80', name], 'probe logs'))
        raise
    finally:
        for name in reversed(created):
            run(['docker', 'rm', '-f', name], 'remove probe container')
        if network_created:
            run(['docker', 'network', 'rm', prefix], 'remove probe network')
        after = [state(n) for n in names]
        report['productionAfter'] = after
        report['productionContainersUnchanged'] = before == after if not args.local else None
        report['existingContainersNotRestarted'] = [(x['id'], x['startedAt']) for x in before] == [(x['id'], x['startedAt']) for x in after]
        report['probeContainersRemoved'] = True
        (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
