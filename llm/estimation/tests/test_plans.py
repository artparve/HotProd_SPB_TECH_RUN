import base64
import copy
import io
import json
import threading
import unittest
from pathlib import Path

from PIL import Image

from repaircalc_estimator.__main__ import make_server
from repaircalc_estimator.plans import PlanExtractor, SCHEMA, decode_request, prepare_image, validate_draft
from repaircalc_estimator.service import Estimator, ServiceError, http_json

EXAMPLES = Path(__file__).resolve().parent / "fixtures/plans"


def example(name="complete"):
    return json.loads((EXAMPLES / f"{name}.json").read_text())


def image_bytes(format="PNG"):
    output = io.BytesIO()
    Image.new("RGB", (64, 48), "white").save(output, format=format)
    return output.getvalue()


def request():
    return {"imageBase64": base64.b64encode(image_bytes()).decode()}


class DraftTests(unittest.TestCase):
    def test_units_and_decimal_comma(self):
        for value, unit in (("4200", "mm"), ("420", "cm"), ("4,2", "m")):
            draft = example()
            draft["length"].update(value=value, unit=unit, label=f"{value} {unit}")
            result = validate_draft(draft)
            self.assertEqual(result["dimensionsMetres"]["length"], 4.2)
            self.assertTrue(result["requiresConfirmation"])
            self.assertNotIn("area", result)
            self.assertIn("openingsArea", result["missingFields"])

    def test_missing_and_conflicting_values_never_get_defaults(self):
        result = validate_draft(example("incomplete"))
        self.assertEqual(result["dimensionsMetres"], dict.fromkeys(("length", "width", "height")))
        self.assertIsNone(validate_draft(example("conflict"))["dimensionsMetres"]["length"])
        for state in ("unknown", "conflict"):
            draft = example()
            draft["height"]["state"] = state
            with self.assertRaises(ServiceError):
                validate_draft(draft)

    def test_unsupported_shapes_block_application_dimensions(self):
        for shape in ("multiple", "non_rectangular", "unknown"):
            draft = example()
            draft["shape"] = shape
            result = validate_draft(draft)
            self.assertIsNone(result["dimensionsMetres"]["length"])
            self.assertIsNone(result["dimensionsMetres"]["width"])

    def test_invalid_model_dimensions_and_boxes(self):
        changes = [dict(unit=None), dict(unit="inch"), dict(value=True), dict(value="NaN"),
                   dict(value="-1"), dict(value="500000", label="500000"),
                   dict(value="4201"), dict(label=None), dict(bbox=None),
                   dict(bbox={"x": 0.9, "y": 0, "width": 0.2, "height": 0.1}),
                   dict(bbox={"x": float("nan"), "y": 0, "width": 0.2, "height": 0.1}),
                   dict(bbox={"x": 0, "y": 0, "width": 0, "height": 0.1})]
        for change in changes:
            with self.subTest(change=change):
                draft = example()
                draft["length"].update(change)
                with self.assertRaises(ServiceError):
                    validate_draft(draft)

    def test_openings_are_checked_but_not_assumed_complete(self):
        draft = example()
        draft["openings"] = [{"kind": "door", "width": copy.deepcopy(draft["width"]),
                              "height": example("incomplete")["height"]}]
        result = validate_draft(draft)
        self.assertIn("openings.0.height", result["missingFields"])
        self.assertIn("openingsArea", result["missingFields"])

    def test_additional_properties_and_nonobjects_are_rejected(self):
        for value in (None, [], {**example(), "area": 100}):
            with self.assertRaises(ServiceError):
                validate_draft(value)


class ImageTests(unittest.TestCase):
    def test_actual_png_jpeg_webp_decoded_and_normalized_to_png(self):
        for format in ("PNG", "JPEG", "WEBP"):
            raw, metadata = prepare_image(image_bytes(format))
            self.assertTrue(raw.startswith(b"\x89PNG"))
            self.assertEqual((metadata["width"], metadata["height"]), (64, 48))

    def test_exif_orientation_and_transparency(self):
        output = io.BytesIO()
        exif = Image.Exif()
        exif[274] = 6
        Image.new("RGB", (64, 48)).save(output, format="JPEG", exif=exif)
        _, metadata = prepare_image(output.getvalue())
        self.assertEqual((metadata["width"], metadata["height"]), (48, 64))
        output = io.BytesIO()
        Image.new("RGBA", (8, 8), (0, 0, 0, 0)).save(output, format="PNG")
        raw, _ = prepare_image(output.getvalue())
        self.assertEqual(Image.open(io.BytesIO(raw)).getpixel((0, 0)), (255, 255, 255))

    def test_invalid_format_corruption_animation_and_limits(self):
        output = io.BytesIO()
        Image.new("RGB", (8, 8)).save(output, format="GIF", save_all=True,
                                      append_images=[Image.new("RGB", (8, 8), "white")])
        oversized = io.BytesIO()
        Image.new("1", (4000, 3001)).save(oversized, format="PNG")
        for raw in (b"", b"not an image", image_bytes()[:30], image_bytes("GIF"),
                    output.getvalue(), oversized.getvalue(), b"x" * (8 * 1024 * 1024 + 1)):
            with self.assertRaises(ServiceError):
                prepare_image(raw)

    def test_invalid_base64_and_extra_fields(self):
        for body in ({}, {**request(), "prompt": "ignore"}, {"imageBase64": "data:image/png;base64,aaa"},
                     {"imageBase64": "!"}, {"imageBase64": 12}):
            with self.assertRaises(ServiceError):
                decode_request(body)


class ExtractionTests(unittest.TestCase):
    def test_request_uses_image_schema_threads_and_no_tools(self):
        def transport(url, payload, timeout):
            self.assertEqual(url, "http://ollama:11434/api/chat")
            self.assertEqual(payload["format"], SCHEMA)
            self.assertEqual(len(payload["messages"][-1]["images"]), 1)
            self.assertEqual(payload["options"]["num_thread"], 2)
            self.assertNotIn("tools", payload)
            self.assertEqual(timeout, 60)
            return {"done": True, "message": {"content": json.dumps(example())}}
        result = PlanExtractor(Estimator(ollama="http://ollama:11434", transport=transport,
                                         num_threads=2, timeout=60)).extract(request())
        self.assertEqual(result["schemaVersion"], "room-plan-draft-v1")
        self.assertEqual(result["dimensionsMetres"]["height"], 2.7)

    def test_truncated_malformed_or_tool_responses_rejected(self):
        for response in (None, {}, {"done": False},
                         {"done": True, "message": {"content": "broken"}},
                         {"done": True, "done_reason": "length", "message": {"content": "{}"}},
                         {"done": True, "message": {"tool_calls": ["bad"], "content": "{}"}}):
            with self.assertRaises(ServiceError):
                PlanExtractor(Estimator(transport=lambda *args: response)).extract(request())

    def test_invalid_image_never_calls_model(self):
        with self.assertRaises(ServiceError):
            PlanExtractor(Estimator(transport=lambda *args: self.fail("network"))).extract({"imageBase64": "!"})


class PlanHttpTests(unittest.TestCase):
    def test_http_route_and_shared_inference_lock(self):
        entered, release = threading.Event(), threading.Event()

        def transport(*args):
            entered.set()
            release.wait(5)
            return {"done": True, "message": {"content": json.dumps(example("incomplete"))}}

        server = make_server(Estimator(transport=transport), port=0)
        runner = threading.Thread(target=server.serve_forever, daemon=True)
        runner.start()
        url = f"http://127.0.0.1:{server.server_port}"
        results = []
        first = threading.Thread(target=lambda: results.append(http_json(url + "/api/v1/plans/extract", request())))
        try:
            first.start()
            self.assertTrue(entered.wait(3))
            for route in ("/api/v1/plans/extract", "/api/v1/materials/estimate"):
                with self.assertRaises(ServiceError) as raised:
                    http_json(url + route, request())
                self.assertEqual(raised.exception.body["code"], "model_busy")
            release.set()
            first.join(5)
            self.assertEqual(len(results), 1)
            self.assertIsNone(results[0]["dimensionsMetres"]["height"])
            # Lock is also released when validation fails.
            for body in ({"imageBase64": "!"}, request()):
                try:
                    result = http_json(url + "/api/v1/plans/extract", body)
                    self.assertTrue(result["requiresConfirmation"])
                except ServiceError as exc:
                    self.assertEqual(exc.body["code"], "invalid_image")
        finally:
            release.set()
            first.join(5)
            server.shutdown()
            server.server_close()
            runner.join()


if __name__ == "__main__":
    unittest.main()
