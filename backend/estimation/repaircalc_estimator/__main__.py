"""Dependency-free CLI and local HTTP service. Run with python -m repaircalc_estimator."""
import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .service import Estimator, ServiceError
from .training import train


def make_server(estimator, host="127.0.0.1", port=8010):
    # One inference at a time prevents requests from queueing indefinitely in Ollama.
    inference_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body):
            encoded = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self):
            if self.path == "/health":
                self.reply(200, {"status": "alive", "model": estimator.model,
                                 "dependenciesChecked": False})
            else:
                self.reply(404, {"code": "not_found"})

        def do_POST(self):
            if self.path not in ("/api/v1/materials/estimate", "/api/v1/materials/calculate"):
                self.reply(404, {"code": "not_found"})
                return
            locked = False
            try:
                if self.headers.get_content_type() != "application/json":
                    raise ServiceError("unsupported_media_type", "Use application/json", 415)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ServiceError("invalid_json", "Invalid Content-Length", 400) from None
                if not 0 < length <= 65536:
                    raise ServiceError("invalid_body_size", "Expected 1..65536 bytes", 413)
                self.connection.settimeout(10)
                try:
                    request = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeError, OSError):
                    raise ServiceError("invalid_json", "Expected a complete JSON body", 400) from None
                if self.path.endswith("/estimate"):
                    locked = inference_lock.acquire(blocking=False)
                    if not locked:
                        raise ServiceError("model_busy", "Another inference is running; retry later", 503)
                    result = estimator.estimate(request)
                else:
                    result = estimator.calculate(request)
                self.reply(200, result)
            except ServiceError as exc:
                self.reply(exc.status, exc.body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                self.reply(500, {"code": "internal_error", "message": "Internal service error"})
            finally:
                if locked:
                    inference_lock.release()

        def log_message(self, format, *args):
            pass

    return ThreadingHTTPServer((host, port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="http://127.0.0.1:8000")
    parser.add_argument("--host", default="http://127.0.0.1:11434", help="Ollama base URL")
    parser.add_argument("--model", default="gemma4:12b")
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--correction", type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("calculate", "estimate"):
        command = commands.add_parser(name)
        command.add_argument("--input", type=Path, required=True)
        command.add_argument("--output", type=Path)
    serve = commands.add_parser("serve")
    serve.add_argument("--listen", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8010)
    training = commands.add_parser("train")
    training.add_argument("--input", type=Path, required=True)
    training.add_argument("--output", type=Path, required=True)
    training.add_argument("--min-samples", type=int, default=5)
    args = parser.parse_args(argv)
    if not 0 < args.timeout <= 3600:
        parser.error("--timeout must be in (0, 3600]")
    try:
        if args.command == "train":
            result = train(json.loads(args.input.read_text(encoding="utf-8")), args.min_samples)
        else:
            artifact = json.loads(args.correction.read_text(encoding="utf-8")) if args.correction else None
            estimator = Estimator(args.backend, args.host, args.model, args.timeout, correction=artifact)
            if args.command == "serve":
                with make_server(estimator, args.listen, args.port) as server:
                    print(f"Listening on http://{args.listen}:{server.server_port}", file=sys.stderr)
                    server.serve_forever()
                return 0
            print(f"{args.command}: Java calculator" + (f" + Ollama {args.model}" if args.command == "estimate" else ""),
                  file=sys.stderr)
            result = getattr(estimator, args.command)(json.loads(args.input.read_text(encoding="utf-8")))
        output = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            args.output.write_text(output, encoding="utf-8")
        else:
            print(output, end="")
        return 0
    except ServiceError as exc:
        print(json.dumps(exc.body, ensure_ascii=False), file=sys.stderr)
        return 1
    except (OSError, ValueError) as exc:
        print(f"Input/output error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
