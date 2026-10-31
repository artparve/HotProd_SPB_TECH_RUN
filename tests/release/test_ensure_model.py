"""Model deployment protocol without Docker, downloads or inference."""
import io
import json
import os
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch
from urllib.error import HTTPError

from scripts.ensure_model import ensure_model


class EnsureModelTests(unittest.TestCase):
    def execute(self, responses, pull=False):
        calls = []

        def transport(request, timeout):
            calls.append((request.full_url, json.loads(request.data)))
            value = responses.pop(0)
            if isinstance(value, Exception):
                raise value
            return io.BytesIO(value if isinstance(value, bytes) else json.dumps(value).encode())

        with patch.dict(os.environ, OLLAMA_URL="http://ollama:11434", OLLAMA_MODEL="test:1",
                        OLLAMA_CONTEXT_LENGTH="16384"), patch("scripts.ensure_model.urlopen", transport), redirect_stdout(io.StringIO()):
            ensure_model(pull)
        return calls

    def missing(self):
        return HTTPError("http://ollama/api/show", 404, "missing", {}, None)

    def test_existing_model_is_loaded_without_repull(self):
        calls = self.execute([{"details": {}}, {"done": True}], pull=True)
        self.assertEqual([url.rsplit("/", 1)[-1] for url, _ in calls], ["show", "generate"])
        self.assertEqual(calls[-1][1]["options"]["num_ctx"], 16384)

    def test_missing_local_model_is_downloaded_then_rechecked(self):
        calls = self.execute([self.missing(), b'{"status":"pulling"}\n{"status":"success"}\n',
                              {"details": {}}, {"done": True}], pull=True)
        self.assertEqual([url.rsplit("/", 1)[-1] for url, _ in calls], ["show", "pull", "show", "generate"])

    def test_missing_external_model_is_not_downloaded(self):
        with self.assertRaisesRegex(RuntimeError, "unavailable"):
            self.execute([self.missing()])

    def test_interrupted_and_failed_downloads_block_acceptance(self):
        for response in (b'{"status":"pulling"}\n', b'{"error":"disk full"}\n'):
            with self.subTest(response=response), self.assertRaises(RuntimeError):
                self.execute([self.missing(), response], pull=True)

    def test_load_failure_and_invalid_metadata_block_acceptance(self):
        for responses in ([{"details": {}}, {"done": False}], [{"details": {}}, []],
                          [{"details": {}}, {"done": True, "error": "oom"}], [{}]):
            with self.subTest(responses=responses), self.assertRaises(RuntimeError):
                self.execute(responses)

    def test_non_missing_http_errors_never_trigger_a_download(self):
        with self.assertRaises(RuntimeError):
            self.execute([HTTPError("http://ollama/api/show", 503, "offline", {}, None)], pull=True)
