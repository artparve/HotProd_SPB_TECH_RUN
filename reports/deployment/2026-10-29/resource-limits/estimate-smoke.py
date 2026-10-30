import json,time,urllib.request
body=json.loads('{"productId": "30473701-b10b-3d56-a15a-8591c16486c4", "input": {"area": 20, "perimeter": 18, "height": 2.7, "reserve": 10, "layers": 2, "quantity": 6}, "prompt": "Сколько краски купить и сколько это стоит? Объясни остаток."}')
def call(path):
    req=urllib.request.Request('http://frontend:8080/api/v1/materials/'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=1250) as response: return json.load(response)
baseline=call('calculate')
started=time.monotonic()
result=call('estimate')
elapsed=time.monotonic()-started
assert result['toolCalls']==['calculate_material_cost'],result
assert result['explanationStatus']=='unverified_model_text',result
assert result['explanation'].strip(),result
for k in ['lines','materials','labor','total','versions']:
    assert result['estimate'][k]==baseline['estimate'][k],k
print(json.dumps({'elapsedSeconds':round(elapsed,2),'validatedAgainstDeterministic':True,'result':result},ensure_ascii=False,indent=2))
