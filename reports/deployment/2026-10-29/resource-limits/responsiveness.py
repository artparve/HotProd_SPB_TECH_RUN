import json,time,statistics,urllib.request
from datetime import datetime,timezone
base='http://frontend:8080'
rows=[]
for i in range(10):
    t=time.monotonic()
    with urllib.request.urlopen(base+'/api/v1/products',timeout=10) as response:
        products=json.load(response)
        assert response.status==200 and len(products)==12
    rows.append({'request':i+1,'seconds':round(time.monotonic()-t,4),'products':len(products)})
    time.sleep(.5)
values=[x['seconds'] for x in rows]
print(json.dumps({'time':datetime.now(timezone.utc).isoformat(),'scope':'Catalog HTTP requests through nginx while LLM estimate is running','count':len(rows),'maxSeconds':max(values),'medianSeconds':statistics.median(values),'samples':rows},indent=2))
