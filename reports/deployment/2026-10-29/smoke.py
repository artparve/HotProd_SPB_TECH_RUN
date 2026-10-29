import json,re,urllib.request,urllib.error
base='http://frontend:8080'
def request(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(base+path,data=data,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=20) as resp:
        return resp.read(),dict(resp.headers)
def getjson(path,body=None):
    data,headers=request(path,body)
    return json.loads(data),headers
assert request('/healthz')[0].strip()==b'ok'
html=request('/')[0].decode()
assets=re.findall(r'(?:src|href)="(/assets/[^\"]+)"',html)
assert assets
for path in assets: assert request(path)[0]
products,headers=getjson('/api/v1/products')
assert len(products)==12,len(products)
assert any(k.lower()=='x-request-id' for k in headers)
product='ac1f222a-f5e9-3b5d-a91e-c943b053e92e'
values={'area':20,'perimeter':18,'height':2.7,'reserve':10,'layers':2,'quantity':6}
estimate,_=getjson('/api/v1/product/'+product+'/calc',values)
assert estimate['total']=='34560.00',estimate
assert str(estimate['lines'][0]['quantity'])=='16'
wrapped,_=getjson('/api/v1/materials/calculate',{'productId':product,'input':values})
assert wrapped['model']=='not-used'
for key in ['total','materials','labor','lines','versions']:
    assert wrapped['estimate'][key]==estimate[key],key
try:
    getjson('/api/v1/materials/calculate',{})
    raise AssertionError('Invalid request accepted')
except urllib.error.HTTPError as exc:
    assert exc.code==422,exc.code
print(json.dumps({'status':'PASS','catalogProducts':len(products),'tilePackages':16,'tileTotal':'34560.00','currency':'RUB','checks':['nginx health','SPA and bundled assets','nginx -> Java -> PostgreSQL','X-Request-ID','nginx -> estimator -> Java deterministic calculation','422 validation through estimator'],'inferenceRun':False},ensure_ascii=False,indent=2))
