"""One admission semaphore covers classification, plans, images and explanations."""
import copy
import hmac
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from repaircalc_estimator.service import Estimator, ServiceError
from .settings import DATA, Settings


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


class Inference:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.transport = transport or self.http
        self.estimator = Estimator(ollama=settings.ollama, model=settings.model,
                                   timeout=settings.timeout, transport=self.transport,
                                   num_threads=settings.threads)
        self.gate = threading.BoundedSemaphore(settings.concurrency)
        self.chain = None

    def http(self, url, payload=None, timeout=30):
        request = Request(url, data=None if payload is None else json.dumps(payload, allow_nan=False).encode(),
                          headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urlopen(request, timeout=timeout) as response:
                data = response.read(self.settings.response_limit + 1)
                if len(data) > self.settings.response_limit:
                    raise ServiceError("invalid_model_response", "Model response too large", 502)
                return strict_json(data)
        except HTTPError as error:
            status = 429 if error.code == 429 else 503
            error.close()
            raise ServiceError("model_busy" if status == 429 else "upstream_unavailable", "Model unavailable", status) from None
        except (URLError, OSError, ValueError, UnicodeError):
            raise ServiceError("upstream_unavailable", "Model unavailable or invalid JSON", 503) from None

    def execute(self, operation, body):
        if operation not in {"classify", "plans/extract", "inspirations/analyze", "explain"}:
            raise ServiceError("not_found", "Unknown operation", 404)
        if not isinstance(body, dict):
            raise ServiceError("invalid_request", "Expected JSON object", 422)
        if not self.gate.acquire(blocking=False):
            raise ServiceError("model_busy", "Inference capacity is occupied", 429)
        try:
            if operation == "classify":
                return self.classify(body)
            if operation == "plans/extract":
                from repaircalc_estimator.plans import PlanExtractor
                return PlanExtractor(self.estimator).extract(body)
            if operation == "inspirations/analyze":
                return self.analyze(body)
            return self.explain(body)
        finally:
            self.gate.release()

    def classify(self, body):
        from repaircalc_classifier.classifier import build_chain, classify
        if self.chain is None:
            s = self.settings
            self.chain = build_chain(model=s.model, base_url=s.ollama, timeout=s.timeout,
                                     num_ctx=s.context, num_predict=s.predict, num_threads=s.threads)
        return classify(body, self.chain, model=self.settings.model, base_url=self.settings.ollama)

    def explain(self, body):
        if set(body) != {"request", "calculation"}:
            raise ServiceError("invalid_request", "Expected request and trusted calculation")
        request, calculation = body["request"], body["calculation"]
        if not isinstance(calculation, dict) or calculation.get("status") != "ok":
            raise ServiceError("invalid_request", "Missing calculation snapshot")
        from repaircalc_estimator.service import validate_request
        product_id, _, _ = validate_request(request)
        self.estimator._check_estimate(calculation.get("estimate"), product_id)
        self.estimator._check_estimate(calculation.get("baseline"), product_id)
        # Per-request instance: never replace a shared estimator's calculate method.
        estimator = copy.copy(self.estimator)
        estimator.calculate = lambda _: copy.deepcopy(calculation)
        result = estimator.estimate(request)
        return {key: result[key] for key in ("model", "explanation", "explanationStatus", "toolCalls")}

    def analyze(self, body):
        import base64
        from jsonschema import Draft202012Validator
        from repaircalc_estimator.plans import decode_request, prepare_image
        image, _ = prepare_image(decode_request(body))
        schema = json.loads((DATA / "inspirations" / "analysis.schema.json").read_text(encoding="utf-8"))
        prompt = (DATA / "inspirations" / "prompt.txt").read_text(encoding="utf-8")
        s = self.settings
        response = self.transport(s.ollama + "/api/chat", {
            "model": s.model, "stream": False, "think": False, "format": schema,
            "messages": [{"role": "system", "content": prompt},
                         {"role": "user", "content": "Определи видимые материалы отделки.",
                          "images": [base64.b64encode(image).decode()]}],
            "options": {"temperature": 0, "num_ctx": s.context,
                        "num_predict": s.predict, "num_thread": s.threads}}, s.timeout)
        try:
            if response.get("done") is not True or response.get("done_reason") == "length":
                raise ValueError()
            result = strict_json(response["message"]["content"])
            Draft202012Validator(schema).validate(result)
        except (KeyError, TypeError, ValueError) as error:
            raise ServiceError("invalid_model_response", "Invalid image observations", 502) from error
        return result


def server(settings: Settings, inference=None):
    inference = inference or Inference(settings)

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(settings.socket_timeout)

        def log_message(self, *args):
            # Request bodies, model text, Authorization and URLs never enter access logs.
            pass

        def respond(self, status, body):
            payload = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            if status == 429:
                self.send_header("Retry-After", "15")
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            self.respond(200 if self.path == "/health" else 404,
                         {"status": "ok"} if self.path == "/health" else {"code": "not_found"})

        def do_POST(self):
            if not settings.token or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + settings.token):
                self.respond(401, {"code": "unauthorized", "message": "Service authentication required"})
                return
            if self.headers.get_content_type() != "application/json":
                self.respond(415, {"code": "unsupported_media_type", "message": "Expected JSON"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= settings.body_limit:
                    raise ServiceError("body_too_large", "Invalid request size", 413)
                payload = self.rfile.read(length)
                if len(payload) != length:
                    raise ValueError("Truncated body")
                body = strict_json(payload)
                prefix = "/internal/v1/"
                if not self.path.startswith(prefix):
                    raise ServiceError("not_found", "Unknown operation", 404)
                result = inference.execute(self.path[len(prefix):], body)
                self.respond(200, result)
            except ServiceError as error:
                self.respond(error.status, error.body)
            except (ValueError, UnicodeError):
                self.respond(422, {"code": "invalid_request", "message": "Invalid JSON or model input"})
            except Exception:
                self.respond(503, {"code": "inference_unavailable", "message": "Inference temporarily unavailable"})

    return ThreadingHTTPServer((settings.host, settings.port), Handler)
