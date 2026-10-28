import copy
import json
import threading
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from repaircalc_estimator.__main__ import make_server
from repaircalc_estimator.service import Estimator, ServiceError, http_json, validate_request
from repaircalc_estimator.training import corrected_input, train

ROOT = Path(__file__).resolve().parents[1]
REQUEST = json.loads((ROOT / "examples/paint.json").read_text())
PRODUCT = REQUEST["productId"]


def estimate():
    # Independently specified contract example from Java ProductCalculationApiIT.
    return {"id": "example", "lines": [{"product": {"id": PRODUCT, "kind": "paint", "unit": "л"},
            "quantity": 2, "need": 5.28, "purchased": 10, "remainder": 4.72, "total": "4980.00"}],
            "materials": "4980.00", "labor": "0.00", "total": "4980.00", "warnings": ["ТЕСТОВЫЕ"],
            "versions": {"catalog": "synthetic-catalog-v1", "rules": "synthetic-rules-v1", "model": "not-used"}}


def rows():
    return [{"orderId": f"synthetic-{i}", "productId": PRODUCT, "kind": "paint", "unit": "л",
             "catalogVersion": "synthetic-catalog-v1", "rulesVersion": "synthetic-rules-v1",
             "baselineNeed": str(i), "actualConsumed": str(Decimal(i) * Decimal("1.1"))}
            for i in range(1, 6)]


class FakeTransport:
    def __init__(self):
        self.calls = []
        self.first = {"role": "assistant", "content": "", "tool_calls": [{"function": {
            "name": "calculate_material_cost", "arguments": {}}}]}
        self.final = {"role": "assistant", "content": "Нужно две банки. Стоимость — по смете."}
        self.estimates = [estimate(), estimate()]
        self.chats = 0

    def __call__(self, url, payload=None, timeout=30):
        self.calls.append((url, copy.deepcopy(payload)))
        if url.endswith("/calc"):
            return copy.deepcopy(self.estimates.pop(0))
        self.chats += 1
        return {"done": True, "done_reason": "stop", "message": self.first if self.chats == 1 else self.final}


class ServiceTests(unittest.TestCase):
    def test_complete_tool_roundtrip_preserves_authoritative_estimate(self):
        transport = FakeTransport()
        result = Estimator(transport=transport).estimate(REQUEST)
        self.assertEqual(result["estimate"], estimate())
        self.assertEqual(result["model"], "gemma4:12b")
        self.assertEqual(len(transport.calls), 3)
        self.assertEqual(transport.calls[1][1]["reserve"], "10")
        self.assertEqual(transport.calls[2][1]["messages"][-1]["role"], "tool")
        self.assertEqual(result["estimate"]["versions"]["model"], "not-used")

    def test_no_call_unknown_tool_or_changed_args_never_calculate(self):
        for first in ({"content": "Стоимость 1 рубль"}, {"tool_calls": []},
                      {"tool_calls": [{"function": {"name": "shell", "arguments": {}}}]},
                      {"tool_calls": [{"function": {"name": "calculate_material_cost", "arguments": {"reserve": 0}}}]}):
            with self.subTest(first=first):
                transport = FakeTransport()
                transport.first = first
                with self.assertRaises(ServiceError):
                    Estimator(transport=transport).estimate(REQUEST)
                self.assertEqual(len(transport.calls), 1)

    def test_explanation_failure_keeps_calculated_result(self):
        transport = FakeTransport()
        transport.final = {"content": ""}
        result = Estimator(transport=transport).estimate(REQUEST)
        self.assertEqual(result["estimate"]["total"], "4980.00")
        self.assertEqual(result["explanationStatus"], "unavailable")

    def test_truncated_model_response_is_rejected(self):
        with self.assertRaises(ServiceError):
            Estimator(transport=lambda *args: {"done": True, "done_reason": "length", "message": {}}).estimate(REQUEST)

    def test_ollama_function_index_is_accepted(self):
        transport = FakeTransport()
        transport.first["tool_calls"][0]["function"]["index"] = 0
        self.assertEqual(Estimator(transport=transport).estimate(REQUEST)["status"], "ok")

    def test_missing_correction_does_not_silently_fall_back(self):
        with self.assertRaises(ServiceError) as raised:
            Estimator(transport=FakeTransport()).calculate({**REQUEST, "useCorrection": True})
        self.assertEqual(raised.exception.body["code"], "correction_unavailable")

    def test_validation_before_network(self):
        for field, bad in (("area", 0), ("reserve", 31), ("layers", 1.5), ("quantity", True),
                           ("height", "NaN"), ("area", "Infinity"), ("area", {})):
            value = copy.deepcopy(REQUEST)
            value["input"][field] = bad
            with self.subTest(field=field, bad=bad), self.assertRaises(ServiceError):
                Estimator(transport=lambda *args: self.fail("network called")).calculate(value)
        with self.assertRaises(ServiceError):
            validate_request({**REQUEST, "productId": "../../etc/passwd"})

    def test_java_errors_and_transport_errors(self):
        import io
        error = HTTPError("url", 422, "invalid", {}, io.BytesIO(
            b'{"code":"needs_clarification","message":"Missing norm","fields":["consumption"]}'))
        with patch("repaircalc_estimator.service.urlopen", side_effect=error):
            with self.assertRaises(ServiceError) as raised:
                http_json("http://localhost/calc", {})
        self.assertEqual(raised.exception.body["fields"], ["consumption"])
        self.assertEqual(raised.exception.status, 422)
        with patch("repaircalc_estimator.service.urlopen", side_effect=TimeoutError()):
            with self.assertRaises(ServiceError) as raised:
                http_json("http://localhost/calc", {})
        self.assertEqual(raised.exception.status, 503)

    def test_calculate_does_not_call_ollama(self):
        transport = FakeTransport()
        result = Estimator(transport=transport).calculate(REQUEST)
        self.assertEqual(transport.chats, 0)
        self.assertEqual(result["model"], "not-used")

    def test_bad_backend_estimate_is_rejected(self):
        for invalid in ({}, {**estimate(), "total": "NaN"}):
            with self.assertRaises(ServiceError):
                Estimator(transport=lambda *args: invalid).calculate(REQUEST)


class TrainingTests(unittest.TestCase):
    def test_fit_known_coefficient_and_apply_without_double_reserve(self):
        artifact = train(rows())
        self.assertEqual(Decimal(artifact["products"][PRODUCT]["factor"]), Decimal("1.1"))
        self.assertEqual(Decimal(artifact["products"][PRODUCT]["trainingMAE"]), 0)
        corrected, audit = corrected_input(artifact, estimate(), validate_request(REQUEST)[1])
        self.assertEqual(Decimal(corrected["reserve"]), 21)
        self.assertEqual(audit["samples"], 5)

    def test_reject_duplicate_sparse_nonfinite_and_mixed_units(self):
        for data in (rows()[:2], rows() + [rows()[0]],
                     [{**r, "actualConsumed": "NaN"} for r in rows()],
                     [rows()[0], *[{**r, "unit": "кг"} for r in rows()[1:]]]):
            with self.subTest(data=data), self.assertRaises(ServiceError):
                train(data)

    def test_reject_incompatible_version_and_unsupported_discrete_wallpaper(self):
        for change in ({"rules": "new"}, {"kind": "wallpaper"}, {"unit": "кг"}):
            artifact = train(rows())
            artifact["products"][PRODUCT].update(change)
            with self.assertRaises(ServiceError):
                corrected_input(artifact, estimate(), validate_request(REQUEST)[1])

    def test_reject_effective_reserve_outside_java_limits(self):
        artifact = train(rows())
        for factor in ("0.5", "1.5"):
            artifact["products"][PRODUCT]["factor"] = factor
            with self.assertRaises(ServiceError):
                corrected_input(artifact, estimate(), validate_request(REQUEST)[1])

    def test_correction_recalculates_via_java_and_detects_snapshot_race(self):
        transport = FakeTransport()
        corrected = transport.estimates[1]
        corrected["lines"][0]["need"] = 5.808
        result = Estimator(transport=transport, correction=train(rows())).calculate({**REQUEST, "useCorrection": True})
        self.assertEqual(result["baseline"]["lines"][0]["need"], 5.28)
        self.assertEqual(result["estimate"]["lines"][0]["need"], 5.808)
        self.assertEqual(Decimal(transport.calls[1][1]["reserve"]), 21)
        transport = FakeTransport()
        transport.estimates[1]["versions"]["catalog"] = "changed"
        with self.assertRaises(ServiceError) as raised:
            Estimator(transport=transport, correction=train(rows())).calculate({**REQUEST, "useCorrection": True})
        self.assertEqual(raised.exception.status, 409)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.server = make_server(Estimator(transport=FakeTransport()), port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_real_http_health_calculate_estimate_and_validation(self):
        self.assertFalse(http_json(self.url + "/health")["dependenciesChecked"])
        for route in ("calculate", "estimate"):
            self.assertEqual(http_json(self.url + "/api/v1/materials/" + route, REQUEST)["estimate"]["total"], "4980.00")
        with self.assertRaises(ServiceError) as raised:
            http_json(self.url + "/api/v1/materials/calculate", {"productId": "bad"})
        self.assertEqual(raised.exception.status, 422)

    def test_invalid_json_and_missing_route(self):
        for route, data, status in (("calculate", b"{", 400), ("missing", b"{}", 404)):
            request = Request(self.url + "/api/v1/materials/" + route, data,
                              {"Content-Type": "application/json"})
            with self.assertRaises(HTTPError) as raised:
                urlopen(request)
            self.assertEqual(raised.exception.code, status)
            raised.exception.close()


if __name__ == "__main__":
    unittest.main()
