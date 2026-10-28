"""Run the real estimator with a transparent local audit of actual upstream calls.

No fake transports/responses: every call delegates to production http_json.
Run from repository root with PYTHONPATH=backend/estimation.
"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
import threading
import time
from uuid import uuid4

from repaircalc_estimator.__main__ import make_server
from repaircalc_estimator.service import Estimator, http_json

ROOT = Path(__file__).resolve().parent
LOCAL = threading.local()


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def recorded_transport(url, payload=None, timeout=30):
    started = time.monotonic()
    entry = {'url': url, 'request': payload, 'startedAt': datetime.now(timezone.utc).isoformat()}
    try:
        response = http_json(url, payload, timeout)
        entry['response'] = response
        return response
    except Exception as error:
        entry['error'] = getattr(error, 'body', {'message': str(error)})
        raise
    finally:
        entry['elapsedSeconds'] = round(time.monotonic() - started, 3)
        if getattr(LOCAL, 'case', None):
            LOCAL.trace.append(entry)
            save(LOCAL.case / 'trace.json', LOCAL.trace)


class AuditedEstimator(Estimator):
    def estimate(self, request):
        LOCAL.case = ROOT / ('case-' + uuid4().hex[:8])
        LOCAL.case.mkdir()
        LOCAL.trace = []
        save(LOCAL.case / 'request.json', request)
        print(f'START {LOCAL.case.name} product={request.get("productId")}', flush=True)
        started = time.monotonic()
        try:
            result = super().estimate(request)
            save(LOCAL.case / 'response.json', result)
            return result
        except Exception as error:
            save(LOCAL.case / 'error.json', getattr(error, 'body', {'message': str(error)}))
            raise
        finally:
            elapsed = round(time.monotonic() - started, 3)
            save(LOCAL.case / 'timing.json', {'elapsedSeconds': elapsed})
            print(f'END {LOCAL.case.name} elapsed={elapsed}s', flush=True)
            LOCAL.case = None


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--listen', default='172.17.0.1')
    parser.add_argument('--port', type=int, default=8010)
    args = parser.parse_args()
    estimator = AuditedEstimator(transport=recorded_transport)
    with make_server(estimator, args.listen, args.port) as server:
        print(f'READY http://{args.listen}:{args.port} model={estimator.model}', flush=True)
        server.serve_forever()
