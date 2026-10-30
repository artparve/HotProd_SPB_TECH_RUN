"""Independent arithmetic against the actual response from the public server."""
import json
from decimal import Decimal, ROUND_CEILING
from pathlib import Path

root = Path(__file__).parent
response = json.loads((root / 'remote-calculation.json').read_text(), parse_float=Decimal)
need = Decimal('20') * Decimal('0.12') * 2 * Decimal('1.10')
quantity = (need / Decimal('2.5')).to_integral_value(rounding=ROUND_CEILING)
expected = dict(need=need, quantity=quantity, purchased=quantity * Decimal('2.5'),
                remainder=quantity * Decimal('2.5') - need, total=quantity * Decimal('1234.50'))
for key, value in expected.items():
    assert Decimal(str(response['lines'][0][key])) == value, key
assert response['versions']['model'] == 'not-used'
print(json.dumps({**{key: str(value) for key, value in expected.items()}, 'model': 'not-used'}, indent=2))
