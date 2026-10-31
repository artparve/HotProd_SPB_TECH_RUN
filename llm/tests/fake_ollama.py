"""Deterministic transport fixture for stack tests; never used by production Compose."""
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from repaircalc_classifier.schema import TAXONOMY


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.reply({"models": []})

    def reply(self, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        schema = body.get("format", {})
        if body.get("tools"):
            message = {"role": "assistant", "content": "", "tool_calls": [
                {"function": {"name": "calculate_material_cost", "arguments": {}}}]}
        elif "floorFamily" in schema.get("properties", {}):
            message = {"role": "assistant", "content": json.dumps(
                {"floorFamily": "tile", "wallFinish": "paint", "warnings": ["Тест транспорта, модель не запускалась."]})}
        elif "shape" in schema.get("properties", {}):
            fixture = Path(__file__).resolve().parents[1] / "estimation/tests/fixtures/plans/complete.json"
            message = {"role": "assistant", "content": fixture.read_text(encoding="utf-8")}
        elif "scope" in schema.get("properties", {}):
            result = {"scope": "in_scope", "work_types": [{"label": "paint", "evidence": [{"field": "description", "quote": "стен"}]}],
                      "surface_locations": [{"label": "wall", "evidence": [{"field": "description", "quote": "стен"}]}],
                      "substrates": [], "properties": {p["id"]: {"state": "unknown", "evidence": []} for p in TAXONOMY["properties"]},
                      "needs_review": True, "review_reasons": ["Тестовый транспорт; нужна проверка человеком."]}
            message = {"role": "assistant", "content": json.dumps(result)}
        else:
            message = {"role": "assistant", "content": "Тестовое пояснение к полученному снимку Java. Упаковки округлены вверх."}
        self.reply({"model": body.get("model", "test-transport"), "done": True,
                    "done_reason": "stop", "message": message, "created_at": "2026-10-30T00:00:00Z"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 11434), Handler).serve_forever()
