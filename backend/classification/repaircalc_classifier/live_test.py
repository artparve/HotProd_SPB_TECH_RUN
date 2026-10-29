"""Small real-inference regression run; expectations are diagnostic, not an F1 benchmark."""
import argparse
import json
import time
from pathlib import Path
from urllib.request import urlopen

from .classifier import build_chain, classify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--host', default='http://127.0.0.1:11434')
    parser.add_argument('--model', default='gemma4:12b')
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    chain = build_chain(args.model, args.host, num_predict=2048)
    with urlopen(args.host.rstrip('/') + '/api/tags', timeout=10) as response:
        models = json.load(response)
    metadata = {'model': args.model, 'host': args.host, 'models': models,
                'thinking': False, 'num_ctx': 16384, 'num_predict': 2048,
                'note': 'Diagnostic manually specified checks, not an independent quality benchmark'}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'run_metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    failures = 0
    with (args.output / 'results.jsonl').open('x', encoding='utf-8') as stream:
        for index, case in enumerate(cases, 1):
            print(f"START {index}/{len(cases)} {case['id']}", flush=True)
            started = time.monotonic()
            row = {'case_id': case['id']}
            try:
                row['result'] = classify(case['product'], chain, model=args.model, base_url=args.host)
                result = row['result']['classification']
                observed = {
                    'scope': result['scope'], 'needs_review': result['needs_review'],
                    **{g: sorted(e['label'] for e in result[g])
                       for g in ['work_types', 'surface_locations', 'substrates']},
                    **{f'property.{k}': v['state'] for k, v in result['properties'].items()},
                }
                row['checks'] = [{
                    'field': k, 'expected': v, 'actual': observed[k],
                    'pass': observed[k] == (sorted(v) if isinstance(v, list) else v),
                } for k, v in case['expected'].items()]
                row['status'] = 'pass' if all(c['pass'] for c in row['checks']) else 'mismatch'
            except Exception as error:
                row.update(status='error', error=str(error))
            row['elapsed_seconds'] = round(time.monotonic() - started, 2)
            failures += row['status'] != 'pass'
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
            stream.flush()
            print(f"DONE {case['id']} {row['status']} {row['elapsed_seconds']}s", flush=True)
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())
