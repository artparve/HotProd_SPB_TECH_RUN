"""Exercise the real role locally with a fake Docker CLI and a loopback HTTP stub."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANSIBLE = shutil.which("ansible-playbook")


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        content = b"[]" if self.path == "/api/v1/products" else b"ok"
        self.send_response(200)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *args):
        pass


@unittest.skipUnless(ANSIBLE and sys.platform != "win32", "requires a Linux/macOS Ansible controller")
class AnsibleRoleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="repaircalc-role-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        bin_dir = self.directory / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "docker"
        fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
record = {"args": args, "config": os.environ.get("DOCKER_CONFIG"),
          "tag": os.environ.get("IMAGE_TAG"), "profiles": os.environ.get("COMPOSE_PROFILES"),
          "frontend_host": os.environ.get("FRONTEND_HOST")}
with open(os.environ["FAKE_DOCKER_LOG"], "a") as log:
    log.write(json.dumps(record) + "\\n")
if args[0] == "info":
    print("28.0.0")
elif args[:2] == ["compose", "version"]:
    print("2.20.0")
elif args[0] == "login":
    secret = sys.stdin.read().strip()
    pathlib.Path(os.environ["DOCKER_CONFIG"], "config.json").write_text(secret)
elif "up" in args and os.environ.get("FAKE_DOCKER_FAIL") == "1":
    print("private-test-registry-password", file=sys.stderr)
    sys.exit(23)
''', encoding="utf-8")
        fake.chmod(0o700)
        (self.directory / "stand.env").write_text("DB_NAME=repaircalc\n", encoding="utf-8")
        (self.directory / "secret.env").write_text("DB_PASSWORD=synthetic-check-only\n", encoding="utf-8")
        self.environ = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                            FAKE_DOCKER_LOG=str(self.directory / "docker.jsonl"),
                            ANSIBLE_CONFIG=str(ROOT / "ansible/ansible.cfg"),
                            REGISTRY_USERNAME="test-user", REGISTRY_PASSWORD="private-test-registry-password")
        self.variables = {
            "release_become": False, "release_environment": "staging",
            "release_image_tag": "sha-" + "a" * 40,
            "release_image_prefix": "ghcr.io/owner/repo",
            "release_id": "sha-" + "a" * 40 + "-20261030T000000Z-1234abcd",
            "release_base": str(self.directory / "stand"),
            "release_env_file": str(self.directory / "stand.env"),
            "release_secret_env_file": str(self.directory / "secret.env"),
            "release_frontend_host": "0.0.0.0",
            "release_frontend_port": self.server.server_port,
        }
        inventory = {"all": {"hosts": {"stand": {"ansible_connection": "local", "ansible_python_interpreter": sys.executable}}}}
        (self.directory / "inventory.json").write_text(json.dumps(inventory), encoding="utf-8")
        (self.directory / "vars.json").write_text(json.dumps(self.variables), encoding="utf-8")

    def deploy(self):
        return subprocess.run([ANSIBLE, "-i", str(self.directory / "inventory.json"),
                               str(ROOT / "ansible/deploy.yml"), "--extra-vars",
                               "@" + str(self.directory / "vars.json")],
                              env=self.environ, capture_output=True, text=True, timeout=120)

    def assert_cleaned(self):
        self.assertFalse((self.directory / "stand/.deploy-lock").exists())
        commands = [json.loads(line) for line in (self.directory / "docker.jsonl").read_text().splitlines()]
        login = next(item for item in commands if item["args"][0] == "login")
        self.assertFalse(Path(login["config"]).exists(), "temporary registry credentials must be removed")
        return commands

    def test_success_waits_without_building_and_marks_current(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.assert_cleaned()
        up = next(item for item in commands if "up" in item["args"])
        self.assertIn("--no-build", up["args"])
        self.assertIn("--wait", up["args"])
        self.assertEqual(up["profiles"], "")
        self.assertEqual(up["tag"], self.variables["release_image_tag"])
        self.assertEqual(up["frontend_host"], "0.0.0.0")
        self.assertTrue((self.directory / "stand/current").is_symlink())
        env_file = self.directory / "stand/current/env/.env"
        self.assertEqual(env_file.stat().st_mode & 0o777, 0o600)
        self.assertNotIn("DB_PASSWORD=", env_file.read_text())
        secret_file = self.directory / "stand/current/env/secret.env"
        self.assertEqual(secret_file.stat().st_mode & 0o777, 0o600)
        self.assertIn("DB_PASSWORD=synthetic-check-only", secret_file.read_text())
        self.assertEqual(up["args"].count("--env-file"), 2)
        self.assertNotIn("private-test-registry-password", result.stdout + result.stderr)

    def test_failed_start_does_not_mark_release_and_always_removes_credentials_and_lock(self):
        previous = self.directory / "previous-release"
        previous.mkdir()
        (self.directory / "stand").mkdir()
        current = self.directory / "stand/current"
        current.symlink_to(previous, target_is_directory=True)
        self.environ["FAKE_DOCKER_FAIL"] = "1"
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assert_cleaned()
        self.assertEqual(current.resolve(), previous)
        self.assertNotIn("private-test-registry-password", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
