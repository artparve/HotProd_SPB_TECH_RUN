"""Capture every original calculation, then compare material lines after publication."""
import json
import sys
import urllib.request
from urllib.error import HTTPError
base='http://194.67.95.67/api/v1'
values=dict(area=20,perimeter=18,height=2.7,reserve=10,layers=2,quantity=6)
def request(path,body=None):
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=10) as response:return response.status,json.load(response)
    except HTTPError as e:return e.code,json.load(e)
products=request('/products')[1]
if isinstance(products,dict): products=products['items']
records=[]
for p in products:
    status,result=request('/product/'+p['id']+'/calc',values)
    records.append({'product':p,'status':status,'result':result})
if len(sys.argv)>1:
    old=json.load(open(sys.argv[1]))
    found={r['product']['id']:r for r in records}
    for before in old:
        after=found[before['product']['id']]
        assert before['product']==after['product'],before['product']['id']
        assert before['status']==after['status']
        if before['status']==200:
            for key in ('lines','materials','labor','total','warnings'):
                assert before['result'].get(key)==after['result'].get(key),(key,before['product']['id'])
        else: assert before['result']['code']==after['result']['code']
    print(json.dumps({'originalProductsUnchanged':len(old),'newCatalogCount':len(records),'calculations':records},ensure_ascii=False,indent=2))
else: print(json.dumps(records,ensure_ascii=False,indent=2))
