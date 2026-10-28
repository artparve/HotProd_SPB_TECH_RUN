"""Verify actual browser-request audits against independent manual examples."""
import json
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CASE_DIRECTORIES = ('case-892c73c1', 'case-5d715b41', 'case-4bac659b', 'case-bf57a81a')
COMMON = {'area': 20, 'perimeter': 18, 'height': 2.7, 'reserve': 10, 'layers': 2, 'quantity': 6}
CASES = {
    '30473701-b10b-3d56-a15a-8591c16486c4': {
        'name': 'paint', 'input': {**COMMON, 'area': 37.5, 'reserve': 12, 'layers': 3},
        'quantity': 4, 'need': '15.12', 'purchased': '20', 'remainder': '4.88', 'total': '9960.00',
    },
    'ac1f222a-f5e9-3b5d-a91e-c943b053e92e': {
        'name': 'tile', 'input': {**COMMON, 'area': 13.7, 'reserve': 8, 'layers': 3},
        'quantity': 11, 'need': '14.796', 'purchased': '15.84', 'remainder': '1.044', 'total': '23760.00',
    },
    'f3b607e5-5758-3db2-bbf2-605dc539184c': {
        'name': 'wallpaper',
        'input': {**COMMON, 'area': 13.7, 'perimeter': 21.4, 'height': 2.6, 'reserve': 7, 'layers': 3},
        'quantity': 8, 'need': '22', 'purchased': '24', 'remainder': '2', 'total': '15120.00',
    },
    '8539fe9d-e2dc-3994-a5fc-85773eb533b5': {
        'name': 'socket', 'input': {**COMMON, 'quantity': 9},
        'quantity': 9, 'need': '9', 'purchased': '9', 'remainder': '0', 'total': '3141.00',
    },
}


def load(path):
    return json.loads(path.read_text(encoding='utf8'))


def main():
    summary = []
    seen = set()
    for case in (ROOT / name for name in CASE_DIRECTORIES):
        request = load(case / 'request.json')
        expected = CASES[request['productId']]
        assert expected['name'] not in seen, 'Unexpected repeated inference; inspect it explicitly'
        seen.add(expected['name'])
        assert request['input'] == expected['input'], (expected['name'], request['input'])
        assert request['useCorrection'] is False
        response = load(case / 'response.json')
        trace = load(case / 'trace.json')
        assert len(trace) == 3, 'Exactly two real model calls and one Java call required'
        assert trace[0]['url'].endswith(':11434/api/chat')
        first = trace[0]['response']
        assert first['done'] is True and first['done_reason'] != 'length'
        calls = first['message']['tool_calls']
        assert len(calls) == 1
        assert calls[0]['function']['name'] == 'calculate_material_cost'
        assert calls[0]['function']['arguments'] == {}
        assert first['eval_count'] > 0
        assert trace[1]['url'].endswith('/product/' + request['productId'] + '/calc')
        assert trace[1]['request'] == {k: str(v) for k, v in request['input'].items()}
        assert trace[2]['url'].endswith(':11434/api/chat')
        assert trace[2]['request']['messages'][-1]['role'] == 'tool'
        assert trace[2]['response']['eval_count'] > 0
        assert trace[2]['request']['messages'][-1]['tool_name'] == 'calculate_material_cost'
        assert response['status'] == 'ok'
        assert response['model'] == 'gemma4:12b'
        assert response['toolCalls'] == ['calculate_material_cost']
        assert response['explanationStatus'] == 'unverified_model_text'
        assert response['explanation'].strip()
        assert response['explanation'] == trace[2]['response']['message']['content']
        estimate = response['estimate']
        assert estimate == trace[1]['response'] == response['baseline']
        assert estimate['mode'] == 'api'
        assert estimate['versions'] == {'catalog': 'synthetic-catalog-v1',
                                       'rules': 'synthetic-rules-v1', 'model': 'not-used'}
        assert response['correction'] == {'applied': False, 'version': 'not-used', 'factor': '1'}
        assert len(estimate['lines']) == 1
        line = estimate['lines'][0]
        assert line['product']['id'] == request['productId']
        assert line['quantity'] == expected['quantity']
        for key in ('need', 'purchased', 'remainder'):
            assert Decimal(str(line[key])) == Decimal(expected[key]), (expected['name'], key, line[key])
        assert line['total'] == estimate['materials'] == estimate['total'] == expected['total']
        assert estimate['labor'] == '0.00'
        assert Decimal(line['product']['price']) * line['quantity'] == Decimal(expected['total'])
        assert Decimal(str(line['purchased'])) - Decimal(str(line['need'])) == Decimal(str(line['remainder']))
        for chat in (trace[0], trace[2]):
            assert chat['request']['model'] == 'gemma4:12b'
            assert chat['request']['stream'] is False
        timing = load(case / 'timing.json')
        summary.append({'name': expected['name'], 'case': case.name, **timing,
                        'quantity': line['quantity'], 'total': estimate['total'],
                        'modelCalls': 2, 'javaCalls': 1, 'verified': True})
        print(f"PASS {expected['name']}: {line['quantity']} packages, {estimate['total']} RUB, {timing['elapsedSeconds']} s")
    assert seen == {'paint', 'tile', 'wallpaper', 'socket'}, seen
    (ROOT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print('PASS: all four browser cases used the real model, tool and Java; exact input and arithmetic preserved.')


if __name__ == '__main__':
    main()
