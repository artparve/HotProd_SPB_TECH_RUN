import base64
import io
import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from PIL import Image
from repaircalc_estimator.service import ServiceError
from repaircalc_llm.service import Inference, server, strict_json
from repaircalc_llm.settings import Settings


TOKEN = "test-service-token-01234567890123456789"
PRODUCT = "ac1f222a-f5e9-3b5d-a91e-c943b053e92e"


class InferenceTests(unittest.TestCase):
    def test_duplicate_keys_and_nonfinite_values_are_rejected(self):
        for payload in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(ValueError):
                strict_json(payload)

    def test_explanation_uses_only_the_java_snapshot(self):
        calls = []
        snapshot = {"lines": [{"product": {"id": PRODUCT}}],
                    "versions": {"catalog": "catalog-1", "rules": "rules-1"}, "total": "34560.00"}
        calculation = {"status": "ok", "estimate": snapshot, "baseline": snapshot,
                       "correction": {"applied": False, "factor": "1", "version": "not-used"}}
        request = {"productId": PRODUCT, "input": {"area": 20, "perimeter": 18, "height": 2.7,
                   "reserve": 10, "layers": 2, "quantity": 6}}

        def transport(url, body, timeout):
            self.assertEqual(url, "http://127.0.0.1:11434/api/chat")
            calls.append(body)
            if len(calls) == 1:
                return {"done": True, "message": {"role": "assistant", "tool_calls": [
                    {"function": {"name": "calculate_material_cost", "arguments": {}}}]}}
            tool_result = json.loads(body["messages"][-1]["content"])
            self.assertEqual(tool_result, calculation)
            return {"done": True, "message": {"content": "Показана проверенная стоимость."}}

        result = Inference(Settings(TOKEN), transport).execute("explain", {"request": request, "calculation": calculation})
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["explanationStatus"], "unverified_model_text")
        self.assertNotIn("estimate", result)
        self.assertEqual(calculation["estimate"]["total"], "34560.00")

    def test_one_gate_is_shared_between_all_operations_and_released_after_errors(self):
        inference = Inference(Settings(TOKEN))
        inference.gate.acquire()
        try:
            for operation in ("classify", "explain", "plans/extract", "inspirations/analyze"):
                with self.assertRaises(ServiceError) as caught:
                    inference.execute(operation, {})
                self.assertEqual(caught.exception.status, 429)
        finally:
            inference.gate.release()
        with self.assertRaises(ServiceError):
            inference.execute("explain", {})
        self.assertTrue(inference.gate.acquire(blocking=False))
        inference.gate.release()

    def test_image_observations_follow_the_closed_schema(self):
        buffer = io.BytesIO()
        Image.new("RGB", (8, 8)).save(buffer, "PNG")
        request = {"imageBase64": base64.b64encode(buffer.getvalue()).decode()}
        valid = {"floorFamily": "tile", "wallFinish": "paint", "warnings": []}

        def transport(*_):
            return {"done": True, "message": {"content": json.dumps(valid)}}

        inference = Inference(Settings(TOKEN), transport)
        self.assertEqual(inference.execute("inspirations/analyze", request), valid)
        valid["price"] = 123
        with self.assertRaises(ServiceError) as caught:
            inference.execute("inspirations/analyze", request)
        self.assertEqual(caught.exception.body["code"], "invalid_model_response")

    def test_environment_requires_service_authentication(self):
        with patch.dict("os.environ", {"INTERNAL_SERVICE_TOKEN": ""}, clear=True):
            with self.assertRaises(ValueError):
                Settings.environment()


class GatewayHttpTests(unittest.TestCase):
    def setUp(self):
        self.application = server(Settings(TOKEN, port=0))
        self.worker = threading.Thread(target=self.application.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.application.server_close)
        self.addCleanup(self.application.shutdown)
        self.url = f"http://127.0.0.1:{self.application.server_port}"

    def post(self, path, payload=b"{}", token=TOKEN):
        request = Request(self.url + path, data=payload,
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        return urlopen(request, timeout=3)

    def test_health_does_not_require_a_loaded_model_and_legacy_routes_are_closed(self):
        with urlopen(self.url + "/health", timeout=3) as response:
            self.assertEqual(json.load(response), {"status": "ok"})
        for path in ("/api/v1/materials/estimate", "/internal/v1/unknown"):
            with self.assertRaises(HTTPError) as caught:
                self.post(path)
            self.assertEqual(caught.exception.code, 404)
            caught.exception.close()

    def test_service_token_and_strict_json_are_required(self):
        for payload, token, expected in ((b"{}", "wrong-token", 401),
                                         (b'{"a":1,"a":2}', TOKEN, 422)):
            with self.assertRaises(HTTPError) as caught:
                self.post("/internal/v1/classify", payload, token)
            self.assertEqual(caught.exception.code, expected)
            caught.exception.close()


if __name__ == "__main__":
    unittest.main()
