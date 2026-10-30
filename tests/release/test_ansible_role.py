"""Exercise the real role locally with a fake Docker CLI and a loopback HTTP stub."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANSIBLE = shutil.which("ansible-playbook")


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        content = b'[ {"id":"synthetic"} ]' if self.path == "/api/v1/products" else b"ok"
        self.send_response(200)
        self.send_header("Content-Type", "application/json" if self.path == "/api/v1/products" else "text/plain")
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
        # The fresh-install preflight must see a FREE port. Start the HTTP stub only
        # when fake `compose up` requests it, just as a real frontend would start.
        # WSL may allow wildcard bind beside a loopback listener; native Linux does not.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            self.frontend_port = probe.getsockname()[1]
        self.server = None
        self.server_error = None
        self.frontend_requested = self.directory / "frontend-requested"
        self.frontend_ready = self.directory / "frontend-ready"
        self.frontend_started = threading.Event()
        self.frontend_stop = threading.Event()
        self.thread = threading.Thread(target=self.serve_frontend_when_requested, daemon=True)
        self.thread.start()
        self.addCleanup(self.cleanup_frontend)
        bin_dir = self.directory / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "docker"
        fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys, time
args = sys.argv[1:]
record = {"args": args, "config": os.environ.get("DOCKER_CONFIG"),
          "tag": os.environ.get("IMAGE_TAG"), "profiles": os.environ.get("COMPOSE_PROFILES"),
          "frontend_host": os.environ.get("FRONTEND_BIND_ADDRESS")}
with open(os.environ["FAKE_DOCKER_LOG"], "a") as log:
    log.write(json.dumps(record) + "\\n")
def legacy(service):
    return {"Id": {"frontend": "a", "backend": "b", "mongo": "c"}[service] * 64,
            "Config": {"Labels": {"com.docker.compose.project": "repaircalc", "com.docker.compose.service": service},
                       "Env": ["MONGO_USER=repaircalc", "MONGO_URI=mongodb://mongo:27017/repaircalc"]},
            "State": {"Status": "running", "Health": {"Status": "healthy"}},
            "HostConfig": {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": os.environ.get("FAKE_LEGACY_PORT", "8080")}]} if service == "frontend" else {}},
            "NetworkSettings": {"Networks": {"repaircalc_default": {"IPAddress": "172.18.0.2"}}},
            "Mounts": [{"Type": "volume", "Name": "repaircalc_mongo-data", "Destination": "/data/db"}] if service == "mongo" else []}
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
    print(json.dumps([legacy(s) for s in ("frontend", "backend", "mongo") if legacy(s)["Id"] in args]))
elif "config" in args and "--format" in args:
    print(json.dumps({"services": {"mongo": {"image": "mongo:8.0", "environment": {"MONGO_INITDB_ROOT_USERNAME": "root", "MONGO_INITDB_ROOT_PASSWORD": "private-root", "MONGO_PORT": "27017", "MONGO_REPLICA_SET": "rs0"}}, "backend": {"environment": {"MONGO_USER": "repaircalc", "MONGO_URI": "mongodb://mongo:27017/repaircalc", "MONGO_PASSWORD": "private-test-db-password"}}}}))
elif "exec" in args:
    if "--eval" in args:
        sys.stdin.read()
        if os.environ.get("FAKE_NETWORK_AUTH_FAIL") == "1":
            print("AuthenticationFailed: private-test-db-password", file=sys.stderr)
            sys.exit(2)
        print(json.dumps({"version": "8.0.15", "replicaSet": "rs0", "primary": True, "initialized": True, "invalidPointer": False}))
    elif "stats" in args:
        print(json.dumps({"catalog_release": 1}))
    elif "backup" in args:
        if os.environ.get("FAKE_BACKUP_FAIL") == "1": sys.exit(1)
        sys.stdout.buffer.write(b"MONGO-test-archive")
elif "ps" in args:
    if "--services" in args: print("backend")
    else: print(json.dumps([{"ID": "b" * 64, "Service": "init-data", "State": "exited", "ExitCode": 1}]))
elif args[0] == "inspect":
    print(json.dumps({"Status": "exited", "ExitCode": 1, "OOMKilled": False, "Health": None}))
elif "logs" in args:
    print("ReplicaSetNoPrimary private-test-db-password private-test-registry-password")
elif "up" in args and os.environ.get("FAKE_DOCKER_FAIL") == "1":
    print("private-test-registry-password", file=sys.stderr)
    sys.exit(23)
elif "up" in args:
    pathlib.Path(os.environ["FAKE_FRONTEND_REQUEST"]).touch()
    for _ in range(200):
        if pathlib.Path(os.environ["FAKE_FRONTEND_READY"]).exists():
            break
        time.sleep(0.05)
    else:
        print("HTTP fixture did not become ready", file=sys.stderr)
        sys.exit(24)
''', encoding="utf-8")
        fake.chmod(0o700)
        (self.directory / "stand.env").write_text("MONGO_DATABASE=repaircalc\n", encoding="utf-8")
        (self.directory / "secret.env").write_text("MONGO_PASSWORD=synthetic-check-only\n", encoding="utf-8")
        self.environ = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                             FAKE_DOCKER_LOG=str(self.directory / "docker.jsonl"),
                             FAKE_FRONTEND_REQUEST=str(self.frontend_requested),
                             FAKE_FRONTEND_READY=str(self.frontend_ready),
                             ANSIBLE_CONFIG=str(ROOT / "docker/ansible/ansible.cfg"),
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
            "release_frontend_port": self.frontend_port,
        }
        inventory = {"all": {"hosts": {"role_test": {"ansible_connection": "local", "ansible_python_interpreter": sys.executable}}}}
        (self.directory / "inventory.json").write_text(json.dumps(inventory), encoding="utf-8")
        (self.directory / "vars.json").write_text(json.dumps(self.variables), encoding="utf-8")

    def serve_frontend_when_requested(self):
        while not self.frontend_stop.wait(0.02):
            if not self.frontend_requested.exists():
                continue
            try:
                self.server = ThreadingHTTPServer(("127.0.0.1", self.frontend_port), HealthHandler)
                if self.frontend_stop.is_set():
                    self.server.server_close()
                    return
                self.frontend_ready.touch()
                self.frontend_started.set()
                self.server.serve_forever(poll_interval=0.05)
            except OSError as error:
                self.server_error = error
                self.frontend_started.set()
            return

    def start_existing_frontend(self):
        self.frontend_requested.touch()
        self.assertTrue(self.frontend_started.wait(10), "Existing frontend fixture did not start")
        self.assertIsNone(self.server_error)

    def cleanup_frontend(self):
        self.frontend_stop.set()
        if self.server is not None and self.frontend_ready.exists():
            self.server.shutdown()
        self.thread.join(timeout=10)
        if self.server is not None:
            self.server.server_close()

    def deploy(self):
        argv = [ANSIBLE, "-i", str(self.directory / "inventory.json"), str(ROOT / "docker/ansible/deploy.yml"),
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

    def assert_cleaned(self, expect_login=True):
        self.assertFalse((self.directory / "stand/.deploy-lock").exists())
        commands = [json.loads(line) for line in (self.directory / "docker.jsonl").read_text().splitlines()]
        logins = [item for item in commands if item["args"][0] == "login"]
        self.assertEqual(bool(logins), expect_login)
        for login in logins:
            self.assertFalse(Path(login["config"]).exists(), "temporary registry credentials must be removed")
        for directory in {item["config"] for item in commands if item.get("config")}:
            self.assertFalse(Path(directory).exists(), "temporary registry directory must be removed even before login")
        return commands

    def assert_successful_install(self):
        self.assertIsNone(self.server, "Fresh installation must not start the frontend before preflight")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.assert_cleaned()
        up = next(item for item in commands if "up" in item["args"])
        self.assertIn("--no-build", up["args"])
        self.assertIn("--wait", up["args"])
        self.assertEqual(up["profiles"], "")
        self.assertEqual(up["tag"], self.variables["release_image_tag"])
        self.assertEqual(up["frontend_host"], self.variables["release_frontend_host"])
        self.assertIsNotNone(self.server)
        self.assertTrue((self.directory / "stand/current").is_symlink())
        env_file = self.directory / "stand/current/env/.env"
        self.assertEqual(env_file.stat().st_mode & 0o777, 0o600)
        self.assertNotIn("MONGO_PASSWORD=", env_file.read_text())
        secret_file = self.directory / "stand/current/env/secret.env"
        self.assertEqual(secret_file.stat().st_mode & 0o777, 0o600)
        self.assertIn("MONGO_PASSWORD=synthetic-check-only", secret_file.read_text())
        self.assertEqual(up["args"].count("--env-file"), 2)
        self.assertNotIn("private-test-registry-password", result.stdout + result.stderr)

    def test_success_waits_without_building_and_marks_current(self):
        self.assert_successful_install()

    def test_fresh_loopback_install_starts_http_only_after_preflight(self):
        # Same-address conflicts are caught even on WSL, unlike wildcard/loopback overlap.
        self.variables["release_frontend_host"] = "127.0.0.1"
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.assert_successful_install()

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
        self.assertIn("database_configuration_error", result.stdout)
        self.assertIn("failure_rc", result.stdout)
        self.assertIn("No automatic rollback", result.stdout)
        report = self.directory / "stand/releases" / self.variables["release_id"] / "deployment-diagnostics.json"
        self.assertEqual(report.stat().st_mode & 0o777, 0o600)
        details = json.loads(report.read_text())
        self.assertEqual(details["failure"]["rc"], 23)
        self.assertIsNone(self.server, "Failed compose up must not start the frontend fixture")
        self.assertIn("private-test-db-password", details["logs"]["init-data"]["stdout"])
        self.assertTrue(all("--tail" in item["args"] and "80" in item["args"] and "--no-color" in item["args"]
                            for item in commands
                             if "logs" in item["args"]))

    def test_prod_adopts_live_legacy_project_backs_up_database_and_skips_sample_import(self):
        self.variables.update(release_environment="prod", release_frontend_host="127.0.0.1")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.environ.update(FAKE_LEGACY="1", FAKE_LEGACY_PORT=str(self.frontend_port))
        self.start_existing_frontend()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.assert_cleaned()
        up = next(item["args"] for item in commands if "up" in item["args"])
        self.assertEqual(up[up.index("--project-name") + 1], "repaircalc")
        self.assertIn(str(self.directory / "stand/releases" / self.variables["release_id"] / "docker/docker-compose.preserve-data.yml"), up)
        pin = json.loads((self.directory / "stand/compose-project.json").read_text())
        self.assertEqual(pin, {"project": "repaircalc", "volume": "repaircalc_mongo-data"})
        backup = self.directory / "stand/current/database-before.archive.gz"
        self.assertEqual(backup.read_bytes(), b"MONGO-test-archive")
        self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        self.assertFalse(any("down" in item["args"] for item in commands))
        self.assertTrue(any("start" in item["args"] and "backend" in item["args"] for item in commands))
        # A fresh release ID preserves the pinned Mongo volume on the next deployment.
        self.variables["release_id"] = self.variables["release_id"].replace("1234abcd", "1234abce")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_failed_database_backup_does_not_restart_services_or_pin_project(self):
        self.variables.update(release_environment="prod", release_frontend_host="127.0.0.1")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.environ.update(FAKE_LEGACY="1", FAKE_LEGACY_PORT=str(self.frontend_port), FAKE_BACKUP_FAIL="1")
        self.start_existing_frontend()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        commands = self.assert_cleaned()
        self.assertFalse(any("up" in item["args"] or "rm" in item["args"] for item in commands))
        self.assertFalse((self.directory / "stand/compose-project.json").exists())
        self.assertIn("Database backup/archive check failed", result.stdout)

    def test_unknown_listener_is_rejected_before_login_or_service_changes(self):
        # Real listener without matching Docker ownership: production guard must stay active.
        self.variables["release_frontend_host"] = "127.0.0.1"
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.start_existing_frontend()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        commands = self.assert_cleaned(expect_login=False)
        self.assertIn("Frontend address/port is unavailable", result.stdout)
        self.assertFalse(any("up" in item["args"] or "rm" in item["args"] or "pull" in item["args"] for item in commands))
        self.assertFalse((self.directory / "stand/current").exists())

    def test_network_authentication_failure_does_not_restart_or_pin_legacy_stack(self):
        self.variables.update(release_environment="prod", release_frontend_host="127.0.0.1")
        (self.directory / "vars.json").write_text(json.dumps(self.variables))
        self.environ.update(FAKE_LEGACY="1", FAKE_LEGACY_PORT=str(self.frontend_port), FAKE_NETWORK_AUTH_FAIL="1")
        self.start_existing_frontend()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        commands = self.assert_cleaned(expect_login=False)
        self.assertIn("Docker network", result.stdout)
        self.assertNotIn("private-test-db-password", result.stdout + result.stderr)
        self.assertFalse(any("up" in item["args"] or "rm" in item["args"] or "pull" in item["args"] for item in commands))
        self.assertFalse((self.directory / "stand/compose-project.json").exists())
        self.assertFalse((self.directory / "stand/current").exists())


if __name__ == "__main__":
    unittest.main()
