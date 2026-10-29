import json,re,hashlib,subprocess
import xml.etree.ElementTree as E
from pathlib import Path
r=Path(__file__).resolve().parent
repo=r.parents[3]
def java(name):
    counts={k:0 for k in ['tests','failures','errors','skipped']}
    for p in (r/name).glob('TEST-*.xml'):
        root=E.parse(p).getroot()
        for k in counts: counts[k]+=int(root.attrib.get(k,0))
    assert counts['tests']>0 and counts['failures']==counts['errors']==counts['skipped']==0,counts
    return counts
def python_count(name):
    text=(r/name).read_text()
    assert text.rstrip().endswith('OK')
    return int(re.search(r'Ran (\d+) tests',text)[1])
front=re.sub(r'\x1b\[[0-9;]*m','',(r/'frontend-tests.log').read_text())
front_count=int(re.search(r'Tests\s+(\d+) passed',front)[1])
e2e=json.loads((r/'e2e-result.json').read_text())['stats']
assert e2e['expected']==14 and not any(e2e[k] for k in ['skipped','unexpected','flaky'])
tests={'javaUnit':java('java-unit'),'javaIntegration':java('java-integration'),'frontendUnit':front_count,'estimatorUnit':python_count('estimator-tests.log'),'classifierUnit':python_count('classifier-tests.log'),'browserDemo':e2e['expected']}
total=tests['javaUnit']['tests']+tests['javaIntegration']['tests']+sum(tests[k] for k in ['frontendUnit','estimatorUnit','classifierUnit','browserDemo'])
samples=[json.loads(line) for line in (r/'resources-final.jsonl').read_text().splitlines()]
def memory_bytes(text):
    m=re.match(r'([\d.]+)([A-Za-z]+)',text.split('/')[0].strip())
    if not m: return 0
    powers={'B':1,'KiB':1024,'MiB':1024**2,'GiB':1024**3,'kB':1000,'MB':1000**2,'GB':1000**3}
    return float(m[1])*powers[m[2]]
names=sorted({c['Name'] for s in samples for c in s['containers']})
resources={}
for name in names:
    rows=[c for s in samples for c in s['containers'] if c['Name']==name]
    resources[name]={'maxSampledCpuPercent':max(float(c['CPUPerc'].rstrip('%')) for c in rows),'maxSampledMemoryMiB':round(max(memory_bytes(c['MemUsage']) for c in rows)/1024**2,2)}
est=json.loads((r/'estimate-result.json').read_text())
assert est['validatedAgainstDeterministic'] and est['result']['explanationStatus']=='unverified_model_text'
cls=json.loads((r/'classification-result.jsonl').read_text().strip())
classification_accepted=cls['status']=='ok'
if classification_accepted:
    answer=cls['classification']
    assert 'paint' in [x['label'] for x in answer['work_types']],answer
    assert {'wall','ceiling'} <= {x['label'] for x in answer['surface_locations']},answer
    assert answer['properties']['moisture_resistant']['state']=='no',answer
limits=json.loads((r/'effective-limits.json').read_text())
for row in limits:
    assert row['health']=='healthy' and not row['oomKilled']
    assert row['cgroup']['memory.events']['oom_kill']==row['cgroup']['memory.events']['oom']==0,row
sources=['docker/docker-compose.yml','backend/estimation/repaircalc_estimator/service.py','backend/estimation/repaircalc_estimator/__main__.py','backend/classification/repaircalc_classifier/classifier.py','backend/classification/repaircalc_classifier/__main__.py']
summary={'status':'PASS' if classification_accepted else 'PARTIAL_PASS','automatedTestsPassed':total,'tests':tests,'apiSmoke':json.loads((r/'smoke-result.json').read_text()),'estimate':{'elapsedSeconds':est['elapsedSeconds'],'total':est['result']['estimate']['total'],'packages':est['result']['estimate']['lines'][0]['quantity'],'toolCalls':est['result']['toolCalls'],'explanationStatus':est['result']['explanationStatus']},'classification':{'status':cls['status'],'accepted':classification_accepted,'error':cls.get('error'),'monitoring':cls.get('monitoring'),'metadata':cls.get('response_metadata',{})},'resources':resources,'resourceSamples':len(samples),'catalogUnderLoad':json.loads((r/'responsiveness.json').read_text()),'source':{'branch':subprocess.check_output(['git','branch','--show-current'],cwd=repo,text=True).strip(),'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'uncommittedFilesSha256':{p:hashlib.sha256((repo/p).read_bytes()).hexdigest() for p in sources}}}
(r/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'status':summary['status'],'automatedTestsPassed':total,'estimate':summary['estimate'],'classification':summary['classification'],'resources':resources},ensure_ascii=False,indent=2))
