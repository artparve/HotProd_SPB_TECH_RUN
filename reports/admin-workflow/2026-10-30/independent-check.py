import json
from decimal import Decimal, ROUND_CEILING
from pathlib import Path
root=Path(__file__).parent
result=json.loads((root/'calculations-after.json').read_text(),parse_float=Decimal)
record=next(x for x in result['calculations'] if x['product']['id']=='d423fa38-ae71-41d5-b895-fc937bab1761')
need=Decimal('20')*Decimal('0.12')*2*(1+Decimal('10')/100)
quantity=(need/Decimal('2.5')).to_integral_value(rounding=ROUND_CEILING)
purchased=quantity*Decimal('2.5');remainder=purchased-need;total=quantity*Decimal('1234.50')
line=record['result']['lines'][0]
for key,value in dict(need=need,quantity=quantity,purchased=purchased,remainder=remainder,total=total).items(): assert Decimal(str(line[key]))==value,(key,line[key],value)
assert record['result']['versions']['model']=='not-used'
assert result['originalProductsUnchanged']==12 and result['newCatalogCount']==13
print(json.dumps({'need':str(need),'quantity':str(quantity),'purchased':str(purchased),'remainder':str(remainder),'total':str(total),'originalProductsUnchanged':12,'newCatalogCount':13,'model':'not-used'},ensure_ascii=False,indent=2))
