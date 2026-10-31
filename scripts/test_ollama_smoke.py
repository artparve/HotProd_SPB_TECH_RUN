"""Offline protocol/progress checks: python3 -m unittest discover -s scripts -p 'test_ollama_smoke.py'."""

import contextlib
import io
import json
import unittest
from unittest.mock import patch

import ollama_smoke as smoke


class PullProgressTest(unittest.TestCase):
    def test_resume_rate_excludes_cached_bytes_and_reports_completion(self):
        progress = smoke.PullProgress()
        output = io.StringIO()
        with patch.object(smoke.time, "monotonic", side_effect=[10, 12, 14]), contextlib.redirect_stderr(output):
            for completed in [512, 768, 1024]:
                progress.update({"status": "pulling a", "digest": "sha256:a", "total": 1024, "completed": completed})
            progress.summary()
        text = output.getvalue()
        self.assertIn("128.0 B/s", text)
        self.assertIn("100.00%", text)
        self.assertIn("ETA 0s", text)
        self.assertIn("sha256:a", text)
        self.assertIn("Observed layers: 1", text)

    def test_interleaved_layers_have_independent_rates_and_updates_are_throttled(self):
        progress = smoke.PullProgress()
        output = io.StringIO()
        with patch.object(smoke.time, "monotonic", side_effect=[0, .1, .2, 2, 3]), contextlib.redirect_stderr(output):
            for digest, completed in [("a", 0), ("b", 500), ("a", 10), ("a", 200), ("b", 1000)]:
                progress.update({"digest": digest, "total": 1000, "completed": completed})
        self.assertEqual(len(output.getvalue().splitlines()), 4)
        self.assertIn("100.0 B/s", output.getvalue())
        self.assertEqual(progress.parts["a"]["baseline"], 0)
        self.assertEqual(progress.parts["b"]["baseline"], 500)

    def test_cached_layer_does_not_claim_network_speed(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            smoke.PullProgress().update({"digest": "cached", "total": 100, "completed": 100})
        self.assertIn("measuring/cached", output.getvalue())
        self.assertIn("100.00%", output.getvalue())

    def test_success_confirms_layers_missing_final_counter_event(self):
        progress = smoke.PullProgress()
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            progress.update({"digest": "layer", "total": 100, "completed": 99})
            progress.update({"status": "success"})
        self.assertIn("ready (pull success)", output.getvalue())
        self.assertIn("100.00%", output.getvalue())
        self.assertEqual(progress.parts["layer"]["completed"], 100)
        self.assertEqual(progress.last_status, "success")

    def test_remote_host_does_not_guess_local_storage(self):
        with patch.object(smoke.subprocess, "run") as run:
            self.assertIn("unknown", smoke.model_directory("http://example.com:11434", None))
            run.assert_not_called()
        self.assertIn("/srv/models", smoke.model_directory("http://example.com", "/srv/models"))


class ProtocolTest(unittest.TestCase):
    def execute(self, responses, args):
        requests = []

        def open_response(request, timeout):
            requests.append((request.full_url, json.loads(request.data)))
            return io.BytesIO(responses.pop(0))

        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(smoke, "urlopen", open_response), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = smoke.main(args)
        return code, stdout.getvalue(), stderr.getvalue(), requests

    def test_pull_then_chat_preserves_utf8_and_json(self):
        answer = {"done": True, "message": {"content": "Грунтовка улучшает сцепление."}}
        code, out, err, requests = self.execute([
            b'{"status":"pulling manifest"}\n{"status":"success"}\n',
            json.dumps(answer, ensure_ascii=False).encode(),
        ], ["--pull", "--models-dir", "/srv/models", "--json", "--prompt", "Привет"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out), answer)
        self.assertIn("/srv/models", err)
        self.assertTrue(requests[0][0].endswith("/api/pull"))
        self.assertTrue(requests[1][0].endswith("/api/chat"))
        self.assertEqual(requests[1][1]["messages"][0]["content"], "Привет")
        self.assertFalse(requests[1][1]["stream"])

    def test_failed_or_interrupted_pull_does_not_start_inference(self):
        for response in [b'{"status":"pulling manifest"}\n', b'{"error":"disk full"}\n']:
            with self.subTest(response=response):
                code, out, _, requests = self.execute([response], ["--pull", "--models-dir", "/srv/models"])
                self.assertEqual(code, 1)
                self.assertEqual(out, "")
                self.assertEqual(len(requests), 1)

    def test_invalid_generation_is_not_reported_as_success(self):
        for response in [b'[]', b'bad JSON', b'{"error":"out of memory"}',
                         b'{"done":true,"message":{"content":" "}}',
                         b'{"done":false,"message":{"content":"partial"}}',
                         b'{"done":true,"done_reason":"length","message":{"content":"cut off"}}']:
            with self.subTest(response=response):
                code, out, err, _ = self.execute([response], [])
                self.assertEqual(code, 1)
                self.assertEqual(out, "")
                self.assertNotIn("OK:", err)


if __name__ == "__main__":
    unittest.main()
