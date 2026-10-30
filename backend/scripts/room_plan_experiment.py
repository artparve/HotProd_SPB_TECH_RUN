#!/usr/bin/env python3
"""Record a real extraction, raw Ollama reply and timing; never downloads models.

Run from repository root with PYTHONPATH=backend/estimation. --docker-container
uses Python's HTTP client inside the existing Ollama container (no published port).
Each run needs a new output directory so earlier evidence cannot be overwritten.
"""
import argparse
import base64
import hashlib
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from repaircalc_estimator.plans import PlanExtractor
from repaircalc_estimator.service import Estimator, ServiceError, http_json

DOCKER_CLIENT = """import json, sys, urllib.request
req = json.load(sys.stdin)
r = urllib.request.Request('http://127.0.0.1:11434'+req['path'],
    data=None if req['body'] is None else json.dumps(req['body']).encode(), headers={'Content-Type':'application/json'})
with urllib.request.urlopen(r, timeout=req['timeout']) as response:
    sys.stdout.write(response.read().decode())
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--host", default="http://127.0.0.1:11434")
    parser.add_argument("--model", default="gemma4:12b")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--num-threads", type=int, default=8)
    parser.add_argument("--docker-container")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error("timeout must be 1..3600")
    args.output_dir.mkdir(parents=True, exist_ok=False)

    def save(name, body):
        (args.output_dir / name).write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def transport(url, payload=None, timeout=30):
        path = url.removeprefix(args.host.rstrip("/"))
        if path == "/api/chat":
            # Record the exact request but keep large base64 out of JSON reports.
            stored = json.loads(json.dumps(payload))
            images = stored["messages"][-1].pop("images")
            (args.output_dir / "prepared.png").write_bytes(base64.b64decode(images[0]))
            stored["messages"][-1]["images"] = ["base64 of prepared.png"]
            save("request.json", stored)
        if args.docker_container:
            result = subprocess.run(["docker", "exec", "-i", args.docker_container, "python3", "-c", DOCKER_CLIENT],
                                    input=json.dumps({"path": path, "body": payload, "timeout": timeout}),
                                    text=True, capture_output=True, timeout=timeout + 15, check=False)
            if result.returncode:
                raise ServiceError("docker_transport_error", result.stderr[-1500:], 503)
            body = json.loads(result.stdout)
        else:
            body = http_json(url, payload, timeout)
        if path == "/api/chat":
            save("raw-response.json", body)
        return body

    started = time.monotonic()
    source = args.image.read_bytes()
    summary = {"startedAt": datetime.now(timezone.utc).isoformat(),
               "source": str(args.image.resolve()), "sha256": hashlib.sha256(source).hexdigest(),
               "model": args.model, "dockerContainer": args.docker_container}
    try:
        summary["ollamaVersion"] = transport(args.host.rstrip("/") + "/api/version", None, 30)
        info = transport(args.host.rstrip("/") + "/api/show", {"model": args.model}, 30)
        summary["modelDetails"] = {key: info.get(key) for key in ("capabilities", "details")}
        tags = transport(args.host.rstrip("/") + "/api/tags", None, 30)
        summary["installedModel"] = next((m for m in tags.get("models", []) if m.get("name") == args.model), None)
        if "vision" not in info.get("capabilities", []):
            raise ServiceError("vision_unavailable", "Installed model has no vision capability", 422)
        estimator = Estimator(ollama=args.host, model=args.model, timeout=args.timeout,
                              transport=transport, num_threads=args.num_threads)
        draft = PlanExtractor(estimator).extract({"imageBase64": base64.b64encode(source).decode("ascii")})
        save("result.json", draft)
        summary["status"] = "validated_draft"
        exit_code = 0
    except (ServiceError, OSError, ValueError, subprocess.TimeoutExpired) as exc:
        summary["status"] = "failed"
        summary["error"] = exc.body if isinstance(exc, ServiceError) else str(exc)
        exit_code = 1
    summary["elapsedSeconds"] = round(time.monotonic() - started, 3)
    save("summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
