"""Real unavailable Ollama endpoint, no generated/stub model response. Test DB only."""
import json
import urllib.request
import http.cookiejar
import psycopg
from repaircalc_classifier.worker import run_one
from repaircalc_classifier.classifier import build_chain, classify
base='http://repaircalc-workflow-api:8000/api/v1/admin'
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
token=''
def call(path,body=None,etag=None):
    headers={'Content-Type':'application/json','X-CSRF-Token':token}
    if etag: headers['If-Match']=etag
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    with client.open(req,timeout=10) as r: return json.load(r),r.headers.get('ETag')
token=call('/session')[0]['csrfToken']
token=call('/login',{'username':'catalog-admin','password':'test-only-password'})[0]['csrfToken']
product,etag=call('/products',{'sourceNamespace':'model-error-check','externalId':'2026-10-30','name':'Ошибка недоступного Ollama — тест'})
job,_=call('/products/'+product['id']+'/classifications',{},etag)
chain=build_chain(model='gemma4:12b',base_url='http://127.0.0.1:9',timeout=2)
def fail(input):return classify(input,chain,model='gemma4:12b',base_url='http://127.0.0.1:9')
for _ in range(3):
    assert run_one('',fail)
    with psycopg.connect('') as conn:
        conn.execute("update repaircalc.admin_classification_job set available_at=now() where id=%s and status='queued'",(job['id'],))
failed=call('/products/'+product['id']+'/classifications')[0][0]
assert failed['status']=='failed' and failed['attempts']==3 and failed['errorCode']=='classification_failed'
assert call('/products/'+product['id'])[0]['snapshot']['name']==product['snapshot']['name']
retry=call('/products/'+product['id']+'/classifications',{},etag)[0]
assert retry['id']!=job['id'] and retry['status']=='queued'
# Also finish the explicit retry against the unavailable endpoint so no test LLM work remains.
for _ in range(3):
    assert run_one('',fail)
    with psycopg.connect('') as conn:
        conn.execute("update repaircalc.admin_classification_job set available_at=now() where id=%s and status='queued'",(retry['id'],))
print(json.dumps({'productId':product['id'],'failedJob':failed,'retryJobId':retry['id'],'cardPreserved':True,'retryCreatesNewJob':True,'testEndpoint':'http://127.0.0.1:9'},ensure_ascii=False,indent=2))
