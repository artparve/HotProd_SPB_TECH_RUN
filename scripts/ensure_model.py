"""Executed inside the LLM container by CD; uses its exact Ollama URL/model.

Only local managed Ollama is allowed to download a missing model. External
servers must be provisioned separately. An installed model is never re-pulled.
"""
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def ensure_model(pull=False):
    base = os.environ["OLLAMA_URL"].rstrip("/")
    model = os.environ["OLLAMA_MODEL"]

    def request(path, body, timeout=600):
        req = Request(base + path, data=json.dumps(body).encode(),
                      headers={"Content-Type": "application/json"})
        return urlopen(req, timeout=timeout)

    try:
        with request("/api/show", {"model": model}) as response:
            metadata = json.load(response)
    except HTTPError as error:
        missing = error.code == 404
        error.close()
        if not missing or not pull:
            raise RuntimeError("Configured model is unavailable; provision Ollama or enable local model download") from None
        # Streaming prevents a multi-GB pull from hitting an idle HTTP timeout.
        completed = False
        with request("/api/pull", {"model": model, "stream": True}, timeout=1800) as response:
            for line in response:
                event = json.loads(line)
                if event.get("error"):
                    raise RuntimeError("Model download failed")
                completed = event.get("status") == "success"
        if not completed:
            raise RuntimeError("Model download was interrupted")
        with request("/api/show", {"model": model}) as response:
            metadata = json.load(response)
    if not isinstance(metadata, dict) or "details" not in metadata:
        raise RuntimeError("Invalid model metadata")
    # Empty generate loads weights without an arbitrary prompt or model answer.
    with request("/api/generate", {"model": model, "stream": False, "keep_alive": "5m",
                                  "options": {"num_ctx": int(os.environ.get("OLLAMA_CONTEXT_LENGTH", "16384"))}}) as response:
        loaded = json.load(response)
    if not isinstance(loaded, dict) or loaded.get("done") is not True or loaded.get("error"):
        raise RuntimeError("Configured model could not load")
    print(json.dumps({"model": model, "status": "ready"}))


if __name__ == "__main__":
    try:
        ensure_model(pull="--pull" in sys.argv)
    except (OSError, URLError, ValueError, RuntimeError) as error:
        # Do not print upstream URLs or response bodies (external URLs may be private).
        print("Model readiness failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
