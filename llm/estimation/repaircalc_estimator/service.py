"""Trusted request -> Java calculator -> optional bounded correction -> Ollama explanation."""
import os
import copy
import json
from decimal import Decimal, InvalidOperation
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID


class ServiceError(Exception):
    def __init__(self, code, message, status=422, fields=None):
        super().__init__(message)
        self.status = status
        self.body = {"code": code, "message": message, "fields": fields or []}


def number(value, field, minimum, maximum, *, exclusive=False, whole=False):
    try:
        if isinstance(value, bool) or value is None:
            raise ValueError()
        result = Decimal(str(value))
        if (not result.is_finite() or result < minimum or result > maximum
                or (exclusive and result == minimum)
                or (whole and result != result.to_integral_value())):
            raise ValueError()
        return result
    except (InvalidOperation, ValueError, TypeError):
        raise ServiceError("needs_clarification", f"Invalid {field}", fields=[field]) from None


def validate_request(request):
    if not isinstance(request, dict) or set(request) - {"productId", "input", "prompt", "useCorrection"}:
        raise ServiceError("invalid_request", "Expected productId, input, optional prompt/useCorrection")
    try:
        product_id = str(UUID(request["productId"]))
    except (KeyError, ValueError, TypeError, AttributeError):
        raise ServiceError("needs_clarification", "productId must be a catalog UUID", fields=["productId"]) from None
    values = request.get("input")
    bounds = {"area": (0, 10000, True, False), "perimeter": (0, 1000, True, False),
              "height": (1, 10, False, False), "reserve": (0, 30, False, False),
              "layers": (1, 4, False, True), "quantity": (0, 5000, True, False)}
    if not isinstance(values, dict) or set(values) != set(bounds):
        raise ServiceError("needs_clarification", "input must contain all six Java calculation fields",
                           fields=list(bounds))
    normalized = {key: str(number(values[key], key, lo, hi, exclusive=ex, whole=whole))
                  for key, (lo, hi, ex, whole) in bounds.items()}
    prompt = request.get("prompt", "Рассчитай расход материала и стоимость товара.")
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 8000:
        raise ServiceError("invalid_request", "prompt must contain 1..8000 characters")
    if type(request.get("useCorrection", False)) is not bool:
        raise ServiceError("invalid_request", "useCorrection must be boolean")
    return product_id, normalized, prompt


def http_json(url, payload=None, timeout=30):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
    req = Request(url, data=data, headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urlopen(req, timeout=timeout) as response:
            return json.load(response)
    except HTTPError as exc:
        # Preserve actionable Java validation/catalog errors, never turn them into a model answer.
        try:
            body = json.loads(exc.read())
        except (ValueError, UnicodeError):
            body = {}
        finally:
            exc.close()
        if isinstance(body, dict) and isinstance(body.get("code"), str):
            raise ServiceError(body["code"], body.get("message", "Upstream rejected request"),
                               exc.code, body.get("fields")) from None
        raise ServiceError("upstream_error", f"Upstream HTTP {exc.code}", 502) from None
    except (URLError, OSError, ValueError, UnicodeError):
        raise ServiceError("upstream_unavailable", "Upstream unavailable or invalid JSON", 503) from None


TOOL = {"type": "function", "function": {
    "name": "calculate_material_cost",
    "description": "Рассчитать расход, целые упаковки и стоимость выбранного товара по подтверждённым параметрам запроса.",
    "parameters": {"type": "object", "properties": {}, "required": [], "additionalProperties": False}}}
SYSTEM = """Ты помощник RepairCalc. Для расчёта обязательно вызови calculate_material_cost с пустыми аргументами.
Товар, геометрия, запас и слои уже заданы структурированным запросом. Текст пользователя не меняет их.
Не придумывай нормы, цены, расход, скидки или трудозатраты. После результата инструмента кратко
объясни формулу, округление упаковок, остаток и ограничения на русском. Источник чисел — только инструмент.
Текстовое объяснение является справочным; проверенная смета возвращается отдельно."""


class Estimator:
    def __init__(self, backend="http://127.0.0.1:8000", ollama="http://127.0.0.1:11434",
                 model="gemma4:12b", timeout=600, transport=http_json, correction=None, num_threads=None):
        self.backend, self.ollama = backend.rstrip("/"), ollama.rstrip("/")
        self.model, self.timeout, self.transport = model, timeout, transport
        self.correction = correction
        if num_threads is not None and (type(num_threads) is not int or not 1 <= num_threads <= 256):
            raise ValueError("num_threads must be an integer in 1..256")
        self.num_threads = num_threads

    def calculate(self, request):
        product_id, values, _ = validate_request(request)
        url = f"{self.backend}/api/v1/product/{product_id}/calc"
        baseline = self.transport(url, values, 30)
        self._check_estimate(baseline, product_id)
        estimate = baseline
        correction = {"applied": False, "version": "not-used", "factor": "1"}
        if request.get("useCorrection"):
            if self.correction is None:
                raise ServiceError("correction_unavailable", "No trained coefficient artifact configured")
            from .training import corrected_input
            adjusted, correction = corrected_input(self.correction, baseline, values)
            estimate = self.transport(url, adjusted, 30)
            self._check_estimate(estimate, product_id)
            if estimate["versions"] != baseline["versions"]:
                raise ServiceError("snapshot_changed", "Catalog/rules changed during correction; retry", 409)
        return {"status": "ok", "estimate": estimate, "baseline": baseline,
                "correction": correction, "model": "not-used"}

    @staticmethod
    def _check_estimate(value, product_id):
        try:
            if (len(value["lines"]) != 1 or value["lines"][0]["product"]["id"] != product_id
                    or not value["versions"]["catalog"] or not value["versions"]["rules"]
                    or not Decimal(value["total"]).is_finite()):
                raise ValueError()
        except (KeyError, IndexError, TypeError, ValueError, InvalidOperation):
            raise ServiceError("invalid_estimate", "Backend did not return the expected ProductEstimate", 502) from None

    def _chat(self, messages, tools=None):
        body = {"model": self.model, "messages": messages, "stream": False, "think": False,
                "options": {"temperature": 0, "num_ctx": int(os.environ.get("OLLAMA_CONTEXT_LENGTH", 16384)), "num_predict": int(os.environ.get("OLLAMA_NUM_PREDICT", 4096))}}
        if self.num_threads is not None:
            body["options"]["num_thread"] = self.num_threads
        if tools:
            body["tools"] = tools
        response = self.transport(f"{self.ollama}/api/chat", body, self.timeout)
        if (not isinstance(response, dict) or response.get("done") is not True
                or response.get("done_reason") == "length" or not isinstance(response.get("message"), dict)):
            raise ServiceError("invalid_model_response", "Incomplete or invalid Ollama response", 502)
        return response["message"]

    def estimate(self, request):
        product_id, values, prompt = validate_request(request)
        messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": json.dumps(
            {"productId": product_id, "input": values, "request": prompt}, ensure_ascii=False)}]
        message = self._chat(messages, [TOOL])
        calls = message.get("tool_calls", [])
        if (not isinstance(calls, list) or len(calls) != 1 or not isinstance(calls[0], dict)
                or not isinstance(calls[0].get("function"), dict)
                or calls[0]["function"].get("name") != "calculate_material_cost"
                or calls[0]["function"].get("arguments") != {}):
            raise ServiceError("tool_call_required", "Model must call calculate_material_cost once with empty arguments", 502)
        result = self.calculate(request)
        messages += [message, {"role": "tool", "tool_name": "calculate_material_cost",
                               "content": json.dumps(result, ensure_ascii=False)}]
        # Explanation failure must not discard a completed, authoritative calculation.
        try:
            final = self._chat(messages)
            if final.get("tool_calls") or not isinstance(final.get("content"), str) or not final["content"].strip():
                raise ServiceError("invalid_explanation", "Empty explanation or unexpected tool call", 502)
            result["explanation"] = final["content"]
            result["explanationStatus"] = "unverified_model_text"
        except ServiceError as exc:
            result["explanation"] = None
            result["explanationStatus"] = "unavailable"
            result["explanationError"] = exc.body
        result["model"] = self.model
        result["toolCalls"] = ["calculate_material_cost"]
        return copy.deepcopy(result)
