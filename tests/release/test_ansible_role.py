"""Exercise the real role locally with a fake Docker CLI and a loopback HTTP stub."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import signal
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
def legacy(service):
    return {"Id": {"frontend": "a", "backend": "b", "postgres": "c"}[service] * 64,
            "Config": {"Labels": {"com.docker.compose.project": "repaircalc", "com.docker.compose.service": service},
                       "Env": ["POSTGRES_USER=repaircalc", "POSTGRES_DB=repaircalc"]},
            "State": {"Status": "running", "Health": {"Status": "healthy"}},
            "HostConfig": {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": os.environ.get("FAKE_LEGACY_PORT", "8080")}]} if service == "frontend" else {}},
            "Mounts": [{"Type": "volume", "Name": "repaircalc_pgdata", "Destination": "/var/lib/postgresql/data"}] if service == "postgres" else []}
if args[0] == "info":
    print("28.0.0")
elif args[:2] == ["compose", "version"]:
    print("2.20.0")
elif args[0] == "login":
    secret = sys.stdin.read().strip()
    pathlib.Path(os.environ["DOCKER_CONFIG"], "config.json").write_text(secret)
elif args[:2] == ["ps", "-aq"]:
    if os.environ.get("FAKE_LEGACY") == "1":
        print("a" * 64 + "\\n" + "b" * 64 + "\\n" + "c" * 64)
elif args[0] == "inspect" and "--format" not in args:
    print(json.dumps([legacy(s) for s in ("frontend", "backend", "postgres") if legacy(s)["Id"] in args]))
elif "config" in args and "--format" in args:
    print(json.dumps({"services": {"postgres": {"image": "postgres:17.11", "environment": {"POSTGRES_USER": "repaircalc", "POSTGRES_DB": "repaircalc", "POSTGRES_PASSWORD": "private-test-db-password"}}, "backend": {"environment": {}}}}))
elif args[0] == "exec":
    if "pg_restore" in args:
        sys.exit(0 if sys.stdin.buffer.read().startswith(b"PGDMP") else 1)
    elif "pg_dump" in " ".join(args):
        if os.environ.get("FAKE_BACKUP_FAIL") == "1":
            sys.exit(1)
        sys.stdout.buffer.write(b"PGDMP-test-archive")
    elif "SHOW server_version_num" in args:
        sys.stdin.read()
        print("170011")
    else:
        sys.stdin.read()
        print("t")
elif "ps" in args:
    print(json.dumps([{"ID": "b" * 64, "Service": "init-data", "State": "exited", "ExitCode": 1}]))
elif args[0] == "inspect":
    print(json.dumps({"Status": "exited", "ExitCode": 1, "OOMKilled": False, "Health": None}))
elif "logs" in args:
    print("FlywayMigrateException private-test-db-password private-test-registry-password")
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
                             ANSIBLE_NO_LOG="True",
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
        argv = [ANSIBLE, "-i", str(self.directory / "inventory.json"), str(ROOT / "ansible/deploy.yml"),
                "--extra-vars", "@" + str(self.directory / "vars.json")]
        process = subprocess.Popen(argv, env=self.environ, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, start_new_session=True)
        try:
            stdout, stderr = process.communicate(timeout=240)
        except subprocess.TimeoutExpired:
            # Kill only this test's process group; do not leave Ansible writing into a cleaned fixture.
            os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate()
            self.fail("Ansible test timed out:\n" + (stdout + stderr)[-6000:])
        return subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)

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
        commands = self.assert_cleaned()
        self.assertEqual(current.resolve(), previous)
        self.assertNotIn("private-test-registry-password", result.stdout + result.stderr)
        self.assertNotIn("private-test-db-password", result.stdout + result.stderr)
        self.assertIn("bootstrap_failed", result.stdout)
        self.assertIn("migration_error", result.stdout)
        self.assertIn("failure_rc", result.stdout)
        self.assertIn("No automatic rollback", result.stdout)
        report = self.directory / "stand/releases" / self.variables["release_id"] / "deployment-diagnostics.json"
        self.assertEqual(report.stat().st_mode & 0o777, 0o600)
        details = json.loads(report.read_text())
        self.assertEqual(details["failure"]["rc"], 23)
        self.assertIn("private-test-db-password", details["logs"]["init-data"]["stdout"])
        self.assertTrue(all("--tail" in item["args"] and "80" in item["args"] and "--no-color" in item["args"]
                            for item in commands
                             if "logs" in item["args"]))

    def test_prod_adopts_live_legacy_project_backs_up_database_and_skips_sample_import(self):
        self.variables.update(release_environment="prod", release_frontend_host="127.0.0.1")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.environ.update(FAKE_LEGACY="1", FAKE_LEGACY_PORT=str(self.server.server_port))
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.assert_cleaned()
        up = next(item["args"] for item in commands if "up" in item["args"])
        self.assertEqual(up[up.index("--project-name") + 1], "repaircalc")
        self.assertIn(str(self.directory / "stand/releases" / self.variables["release_id"] / "docker/docker-compose.preserve-data.yml"), up)
        pin = json.loads((self.directory / "stand/compose-project.json").read_text())
        self.assertEqual(pin, {"project": "repaircalc", "volume": "repaircalc_pgdata"})
        backup = self.directory / "stand/current/database-before.dump"
        self.assertEqual(backup.read_bytes(), b"PGDMP-test-archive")
        self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        self.assertFalse(any("stop" in item["args"] or "down" in item["args"] for item in commands))
        # The pin survives a retry and continues to select the real DB, not repaircalc_prod_pgdata.
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_failed_database_backup_does_not_restart_services_or_pin_project(self):
        self.variables.update(release_environment="prod", release_frontend_host="127.0.0.1")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.environ.update(FAKE_LEGACY="1", FAKE_LEGACY_PORT=str(self.server.server_port), FAKE_BACKUP_FAIL="1")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        commands = self.assert_cleaned()
        self.assertFalse(any("up" in item["args"] or "rm" in item["args"] for item in commands))
        self.assertFalse((self.directory / "stand/compose-project.json").exists())
        self.assertIn("Database backup/archive check failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
