#!/usr/bin/env python3
"""Standalone text inference smoke test for Gemma 4 12B through Ollama (stdlib only)."""

import argparse
import json
import math
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("expected a positive integer")
    return number


def positive_timeout(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("expected a finite positive timeout")
    return number


def decode_object(data):
    result = json.loads(data)
    if not isinstance(result, dict):
        raise ValueError("Ollama returned a JSON value instead of an object")
    if result.get("error"):
        raise ValueError(str(result["error"]))
    return result


def post(host, endpoint, payload, timeout):
    request = Request(
        host + endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    return urlopen(request, timeout=timeout)


def human_bytes(value):
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(value) < 1024 or unit == "TiB":
            return f"{value:.1f} {unit}"
        value /= 1024


def model_directory(host, supplied):
    if supplied:
        return f"{supplied} (server path supplied with --models-dir)"
    local = urlsplit(host).hostname in ("localhost", "127.0.0.1", "::1")
    if local and Path("/snap/ollama/current/meta/snap.yaml").is_file() and shutil.which("snap"):
        try:
            result = subprocess.run(["snap", "get", "ollama", "models"],
                                    capture_output=True, text=True, timeout=3, check=False)
            if result.returncode == 0 and result.stdout.strip():
                return f"{result.stdout.strip()} (local Snap configuration; verify server uses Snap)"
        except (OSError, subprocess.TimeoutExpired):
            pass
    return "unknown: API does not expose server storage; pass --models-dir after checking the server"


class PullProgress:
    """Rate of observed progress per digest; already cached bytes are excluded."""

    def __init__(self):
        self.parts = {}
        self.last_status = None

    def update(self, event):
        now = time.monotonic()
        status = event.get("status")
        if status == "success":
            # Ollama can omit the final byte-counter event before changing layers.
            # A successful pull confirms all announced blobs were verified/stored.
            for digest, part in list(self.parts.items()):
                if not part["finished"]:
                    self.update({"status": "ready (pull success)", "digest": digest,
                                 "total": part["total"], "completed": part["total"]})
        digest = event.get("digest")
        total, completed = event.get("total"), event.get("completed", 0)
        if digest and isinstance(total, int) and total > 0 and isinstance(completed, int):
            completed = max(0, min(completed, total))
            part = self.parts.get(digest)
            if part is None or completed < part["completed"]:
                part = {"start": now, "baseline": completed, "printed": float("-inf"),
                        "completed": completed, "total": total, "finished": False}
                self.parts[digest] = part
            elapsed = now - part["start"]
            rate = (completed - part["baseline"]) / elapsed if elapsed > 0 else 0
            part.update(completed=completed, total=total)
            finished = completed == total
            if (now - part["printed"] >= 1 and not part["finished"]) or (finished and not part["finished"]):
                ratio = completed / total
                bar = "#" * int(ratio * 20) + "-" * (20 - int(ratio * 20))
                eta = f"{(total - completed) / rate:.0f}s" if rate > 0 else "unknown"
                speed = f"{human_bytes(rate)}/s" if elapsed >= 1 or rate > 0 else "measuring/cached"
                print(f"{status or 'pulling'} | {digest} | [{bar}] {ratio:6.2%} | "
                      f"{human_bytes(completed)} / {human_bytes(total)} | "
                      f"{speed} | ETA {'0s' if finished else eta}", file=sys.stderr, flush=True)
                part["printed"] = now
            part["finished"] = finished
        elif status and status != self.last_status:
            print(status, file=sys.stderr, flush=True)
        if status:
            self.last_status = status

    def summary(self):
        completed = sum(part["completed"] for part in self.parts.values())
        total = sum(part["total"] for part in self.parts.values())
        print(f"Observed layers: {len(self.parts)}, {human_bytes(completed)} / {human_bytes(total)} "
              "(includes cached bytes).", file=sys.stderr, flush=True)


def pull_model(host, model, timeout, models_dir=None):
    print(f"Downloading/checking {model} ...", file=sys.stderr)
    print(f"Download server: {host}\nModel storage: {model_directory(host, models_dir)}\n"
          "Layers: <storage>/blobs/sha256-<digest>; manifests: <storage>/manifests/.\n"
          "Speed: average since first observed event per layer, excluding initial cached bytes.",
          file=sys.stderr, flush=True)
    progress = PullProgress()
    with post(host, "/api/pull", {"model": model, "stream": True}, timeout) as response:
        for line in response:
            if not line.strip():
                continue
            event = decode_object(line)
            progress.update(event)
    progress.summary()
    if progress.last_status != "success":
        raise ValueError("model download ended without a success status; retry --pull")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="http://127.0.0.1:11434", help="Ollama server URL")
    parser.add_argument("--model", default="gemma4:12b", help="Ollama model tag")
    parser.add_argument("--pull", action="store_true", help="download model before inference")
    parser.add_argument("--models-dir", help="verified model storage path ON THE SERVER (display only)")
    parser.add_argument("--prompt", default="Ответь по-русски одним предложением: для чего нужна грунтовка стен?")
    parser.add_argument("--timeout", type=positive_timeout, default=600, help="socket timeout in seconds")
    parser.add_argument("--num-predict", type=positive_int, default=512, help="maximum generated tokens")
    parser.add_argument("--num-ctx", type=positive_int, default=4096, help="context window size")
    parser.add_argument("--json", action="store_true", help="print complete API response as JSON")
    args = parser.parse_args(argv)
    host = args.host.rstrip("/")
    parsed = urlsplit(host)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query or parsed.fragment:
        parser.error("--host must be an http(s) URL without query or fragment")
    if not args.prompt.strip() or not args.model.strip():
        parser.error("--prompt and --model must not be empty")

    try:
        if args.pull:
            pull_model(host, args.model, args.timeout, args.models_dir)
        print(f"Running {args.model} via {host} ...", file=sys.stderr, flush=True)
        started = time.monotonic()
        with post(host, "/api/chat", {
            "model": args.model,
            "messages": [{"role": "user", "content": args.prompt}],
            "stream": False,
            "think": False,
            "keep_alive": "5m",
            "options": {"num_ctx": args.num_ctx, "num_predict": args.num_predict},
        }, args.timeout) as response:
            result = decode_object(response.read())
        message = result.get("message")
        content = message.get("content") if isinstance(message, dict) else None
        if result.get("done") is not True or not isinstance(content, str) or not content.strip():
            raise ValueError("no completed non-empty answer in message.content")
        if result.get("done_reason") == "length":
            raise ValueError("answer truncated by token limit; increase --num-predict")
        print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else content)
        print(f"OK: model={result.get('model', args.model)}, "
              f"elapsed={time.monotonic() - started:.2f}s, "
              f"generated_tokens={result.get('eval_count', 'unknown')}", file=sys.stderr)
        return 0
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:2000]
        print(f"Ollama HTTP {error.code}: {detail}", file=sys.stderr)
        if error.code == 404:
            print("Check --model; use --pull to download it. Check --host points to Ollama.", file=sys.stderr)
    except (URLError, OSError) as error:
        print(f"Cannot reach/read Ollama at {host}: {error}. "
              "Start 'ollama serve', check --host, or increase --timeout.", file=sys.stderr)
    except ValueError as error:
        print(f"Ollama response error: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
