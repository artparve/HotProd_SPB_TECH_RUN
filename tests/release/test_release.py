"""No SSH, Docker, real secrets or application services are used by these tests."""

import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        base = None
        self.directory = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.environ = {
            "DEPLOY_HOST": "stand.example.org", "DEPLOY_USER": "deploy",
            "REGISTRY_USERNAME": "ci-user", "REGISTRY_PASSWORD": "private-registry-value",
            "MONGO_PASSWORD": "private-db-value",
            "MONGO_ROOT_PASSWORD": "private-root", "MONGO_KEYFILE_SECRET": "synthetic-base64-key", "INTERNAL_SERVICE_TOKEN": "synthetic-012345678901234567890123456789",
            "PATH": os.environ.get("PATH", ""),
        }
        for setting in ("DEPLOY_SSH_KEY_FILE",):
            file = root / setting
            file.write_text("private-file-content\n", encoding="utf-8")
            self.environ[setting] = str(file)
        self.argv = ["--environment", "staging", "--image-tag", "sha-" + "a" * 40,
                     "--image-prefix", "ghcr.io/owner/repaircalc"]

    def config(self, **overrides):
        values = {"environment": "staging", "image_tag": "sha-" + "a" * 40,
                  "image_prefix": "ghcr.io/owner/repaircalc", "no_become": False}
        values.update(overrides)
        return release.configuration(argparse.Namespace(**values), self.environ)

    def test_ssh_accepts_new_keys_without_requiring_a_known_hosts_secret(self):
        inventory, variables = self.config()
        host = inventory["all"]["hosts"]["stand"]
        self.assertIn("StrictHostKeyChecking=accept-new", host["ansible_ssh_common_args"])
        self.assertNotIn("UserKnownHostsFile", host["ansible_ssh_common_args"])
        self.assertIn("BatchMode=yes", host["ansible_ssh_common_args"])
        self.assertEqual(variables["release_environment"], "staging")
        self.assertRegex(variables["release_id"], r"^sha-a{40}-\d{8}T\d{6}Z-[0-9a-f]{8}$")
        self.assertNotIn("private-registry-value", json.dumps(variables))

    def test_rejects_unsafe_environment_and_image_inputs(self):
        for key, value in [("environment", "../production"), ("environment", "prod;id"),
                           ("image_tag", "latest"), ("image_tag", "sha-abc"),
                           ("image_prefix", "ghcr.io/Owner/Repo"),
                           ("image_prefix", "other.invalid/owner/repo")]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.config(**{key: value})

    def test_rejects_invalid_host_username_port_and_missing_files(self):
        for key, value in [("DEPLOY_HOST", "-oProxyCommand=id"), ("DEPLOY_USER", "root;id"),
                           ("DEPLOY_PORT", "0"), ("DEPLOY_FRONTEND_PORT", "65536"),
                           ("DEPLOY_SSH_KEY_FILE", str(Path(self.directory.name, "missing")))]:
            with self.subTest(key=key), patch.dict(self.environ, {key: value}), self.assertRaises(ValueError):
                self.config()

    def test_supports_ipv6_and_custom_ports_without_shell(self):
        self.environ.update(DEPLOY_HOST="2001:db8::1", DEPLOY_PORT="2222", DEPLOY_FRONTEND_PORT="8081")
        inventory, variables = self.config()
        self.assertEqual(inventory["all"]["hosts"]["stand"]["ansible_port"], 2222)
        self.assertEqual(variables["release_frontend_port"], 8081)

    def test_frontend_binding_defaults_to_loopback_for_manual_deployment(self):
        _, variables = self.config()
        self.assertEqual(variables["release_frontend_host"], "127.0.0.1")
        for host in ("0.0.0.0", "127.0.0.1", "192.0.2.10", "::", "::1"):
            with self.subTest(host=host), patch.dict(self.environ, {"DEPLOY_FRONTEND_HOST": host}):
                _, variables = self.config()
                self.assertEqual(variables["release_frontend_host"], host)

    def test_rejects_frontend_binding_with_a_port_or_invalid_address(self):
        for host in ("0.0.0.0:8080", "http://example.org", "example.org", "", "0.0.0.0\nOTHER=value"):
            with self.subTest(host=host), patch.dict(self.environ, {"DEPLOY_FRONTEND_HOST": host}), self.assertRaises(ValueError):
                self.config()

    def test_dry_run_never_calls_ansible_and_never_prints_secrets(self):
        output = io.StringIO()
        with patch("scripts.release.subprocess.run") as run, redirect_stdout(output):
            self.assertEqual(release.main(self.argv + ["--dry-run"], self.environ), 0)
        run.assert_not_called()
        self.assertNotIn("private-", output.getvalue())

    def test_ai_release_requires_token_before_connecting_and_supports_gpu(self):
        self.environ.update(DEPLOY_LLM_ENABLED="true", DEPLOY_OLLAMA_GPU="true")
        _, variables = self.config()
        self.assertTrue(variables["release_llm_enabled"])
        self.assertTrue(variables["release_ollama_gpu"])
        self.environ["INTERNAL_SERVICE_TOKEN"] = "short"
        with self.assertRaisesRegex(ValueError, "INTERNAL_SERVICE_TOKEN"):
            self.config()

    def test_passes_inventory_as_json_and_preserves_ansible_exit_code(self):
        seen = []

        def run(command, **kwargs):
            inventory = json.loads(Path(command[2]).read_text())
            variables = json.loads(Path(command[-1][1:]).read_text())
            self.assertEqual(inventory["all"]["hosts"]["stand"]["ansible_user"], "deploy")
            env_file = Path(variables["release_env_file"])
            self.assertEqual(env_file.read_text(encoding="utf-8"), release.deployment_dotenv(self.environ))
            secret_file = Path(variables["release_secret_env_file"])
            self.assertEqual(secret_file.read_text(encoding="utf-8"), release.deployment_secret_dotenv(self.environ))
            self.assertNotIn("private-db-value", env_file.read_text())
            self.assertNotIn("private-db-value", json.dumps(variables))
            self.assertNotIn("MONGO_PASSWORD", kwargs["env"])
            self.assertEqual(kwargs["env"]["ANSIBLE_HOST_KEY_CHECKING"], "True")
            self.assertEqual(kwargs["env"]["ANSIBLE_NO_LOG"], "True")
            self.assertNotIn("shell", kwargs)
            self.assertNotIn("private-registry-value", " ".join(command))
            self.assertNotIn("private-db-value", " ".join(command))
            seen.append(Path(command[2]))
            seen.append(env_file)
            seen.append(secret_file)
            return subprocess_result(7)

        with patch("scripts.release.sys.platform", "linux"), patch("scripts.release.shutil.which", return_value="/bin/ansible-playbook"), patch("scripts.release.subprocess.run", side_effect=run), redirect_stdout(io.StringIO()):
            self.assertEqual(release.main(self.argv, self.environ), 7)
        self.assertFalse(seen[0].exists())
        self.assertFalse(seen[1].exists())
        self.assertFalse(seen[2].exists())

    def test_generates_only_allowlisted_settings_with_defaults(self):
        self.environ["UNRELATED_SECRET"] = "do-not-copy"
        dotenv = release.deployment_dotenv(self.environ)
        self.assertNotIn('MONGO_PASSWORD=', dotenv)
        self.assertNotIn('ADMIN_PASSWORD_HASH=', dotenv)
        secret_dotenv = release.deployment_secret_dotenv(self.environ)
        self.assertIn('MONGO_PASSWORD="private-db-value"\n', secret_dotenv)
        self.assertNotIn('ADMIN_USERNAME=', secret_dotenv)
        self.assertIn('ADMIN_USERNAME=""\n', dotenv)
        self.assertIn('ADMIN_COOKIE_SECURE="true"\n', dotenv)
        self.assertIn('OLLAMA_NUM_THREADS="8"\n', dotenv)
        self.assertNotIn("do-not-copy", dotenv)
        self.assertNotIn("private-registry-value", dotenv)

    def test_required_password_and_multiline_values_fail_without_printing_secrets(self):
        for name, value in [("MONGO_PASSWORD", ""), ("MONGO_PASSWORD", "private-db-value\nINJECTED=value"),
                            ("ADMIN_PASSWORD_HASH", "private-value\r"), ("OLLAMA_URL", "private-value\x00")]:
            with self.subTest(name=name, value=value), patch.dict(self.environ, {name: value}):
                output = io.StringIO()
                with redirect_stderr(output):
                    self.assertEqual(release.main(self.argv + ["--dry-run"], self.environ), 2)
                self.assertNotIn("private-", output.getvalue())

    def test_workflow_has_an_individual_setting_for_every_runtime_variable(self):
        workflow = (release.ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")
        for name in release.DEPLOYMENT_ENV_DEFAULTS:
            self.assertRegex(workflow, rf"(?m)^\s+{name}:")
        self.assertIn("REGISTRY_PASSWORD: ${{ github.token }}", workflow)

    def test_shared_templates_separate_secret_keys_and_cover_release_settings(self):
        public_text = (release.ROOT / "env/.env.example").read_text(encoding="utf-8")
        secret_text = (release.ROOT / "env/secret.env.example").read_text(encoding="utf-8")
        public_keys = set(re.findall(r"^([A-Z][A-Z0-9_]*)=", public_text, re.MULTILINE))
        secret_keys = set(re.findall(r"^([A-Z][A-Z0-9_]*)=", secret_text, re.MULTILINE))
        self.assertEqual(secret_keys, set(release.DEPLOYMENT_SECRET_DEFAULTS))
        self.assertTrue(set(release.DEPLOYMENT_PUBLIC_DEFAULTS) <= public_keys)
        self.assertFalse(public_keys & secret_keys)
        self.assertFalse((release.ROOT / "backend/.env.example").exists())

    @unittest.skipUnless(shutil.which("docker"), "requires Compose CLI, not Docker Engine")
    def test_preserve_data_override_refreshes_gallery_but_keeps_selected_images_and_database(self):
        version = subprocess.run([shutil.which("docker"), "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=30)
        if version.returncode:
            self.skipTest("Compose CLI unavailable")
        env = {name: value for name, value in os.environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        env.update(MONGO_PASSWORD="configuration-only", MONGO_ROOT_PASSWORD="configuration-only-root",
                   MONGO_KEYFILE_SECRET="configuration-only-key", IMAGE_PREFIX="ghcr.io/owner/repo", IMAGE_TAG="sha-" + "a" * 40,
                   FRONTEND_HOST="127.0.0.1", FRONTEND_PORT="8080", COMPOSE_PROFILES="")
        result = subprocess.run([
            shutil.which("docker"), "compose", "-p", "repaircalc", "--env-file", "env/.env.example",
            "--env-file", "env/secret.env.example", "-f", "docker/docker-compose.yml", "-f", "docker/docker-compose.release.yml",
            "-f", "docker/docker-compose.preserve-data.yml", "config", "--format", "json",
        ], cwd=release.ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        config = json.loads(result.stdout)
        self.assertEqual(config["services"]["init-data"]["entrypoint"],
                         ["java", "-jar", "/app/backend.jar", "--repaircalc.command=import-inspirations"])
        self.assertEqual(config["services"]["backend"]["depends_on"]["init-data"]["condition"], "service_completed_successfully")
        self.assertEqual(config["volumes"]["mongo-data"]["name"], "repaircalc_mongo-data")
        self.assertEqual(config["services"]["backend"]["image"], "ghcr.io/owner/repo/backend:sha-" + "a" * 40)

    @unittest.skipUnless(shutil.which("docker"), "requires Compose CLI, not Docker Engine")
    def test_release_compose_publishes_configured_frontend_interface(self):
        version = subprocess.run([shutil.which("docker"), "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=30)
        if version.returncode:
            self.skipTest("Compose CLI is not available in this controller environment")
        env = {name: value for name, value in os.environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        env.update({key: "configuration-check-only" for key in release.DEPLOYMENT_SECRET_DEFAULTS}, IMAGE_PREFIX="ghcr.io/owner/repo",
                   IMAGE_TAG="sha-" + "a" * 40, FRONTEND_PORT="8080", COMPOSE_PROFILES="")
        for host in ("0.0.0.0", "127.0.0.1", "::"):
            with self.subTest(host=host):
                env["FRONTEND_BIND_ADDRESS"] = host
                result = subprocess.run([
                    shutil.which("docker"), "compose", "--env-file", "env/.env.example", "--env-file", "env/secret.env.example",
                    "-f", "docker/docker-compose.yml", "-f", "docker/docker-compose.release.yml",
                    "config", "--format", "json",
                ], cwd=release.ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)
                port = json.loads(result.stdout)["services"]["frontend"]["ports"][0]
                self.assertEqual(port["host_ip"], host)
                self.assertEqual(str(port["published"]), "8080")
                self.assertEqual(port["target"], 8080)

    @unittest.skipUnless(shutil.which("docker"), "requires Compose CLI, not Docker Engine")
    def test_two_env_files_configure_database_and_ollama_without_exposing_secrets_in_public_file(self):
        version = subprocess.run([shutil.which("docker"), "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=30)
        if version.returncode:
            self.skipTest("Compose CLI is not available in this controller environment")
        values = dict(self.environ, MONGO_DATABASE="stand_db", MONGO_USER="stand_user", DB_URL="jdbc:postgresql://127.0.0.1:55432/wrong",
                      MONGO_IMAGE_TAG="8.0", OLLAMA_MEMORY_LIMIT="6g")
        public_file = Path(self.directory.name, ".env")
        secret_file = Path(self.directory.name, "secret.env")
        public_file.write_text(release.deployment_dotenv(values))
        secret_file.write_text(release.deployment_secret_dotenv(values))
        env = {name: value for name, value in os.environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        env.update(IMAGE_PREFIX="ghcr.io/owner/repo", IMAGE_TAG="sha-" + "a" * 40, COMPOSE_PROFILES="")
        result = subprocess.run([
            shutil.which("docker"), "compose", "--env-file", str(public_file), "--env-file", str(secret_file),
            "-f", "docker/docker-compose.yml", "-f", "docker/docker-compose.release.yml", "--profile", "llm",
            "config", "--format", "json",
        ], cwd=release.ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        services = json.loads(result.stdout)["services"]
        self.assertEqual(services["mongo-init"]["environment"]["MONGO_DATABASE"], "stand_db")
        self.assertEqual(services["mongo-init"]["environment"]["MONGO_USER"], "stand_user")
        self.assertEqual(services["backend"]["environment"]["MONGO_URI"], "mongodb://mongo:27017/stand_db?replicaSet=rs0&directConnection=true")
        self.assertEqual(services["backend"]["environment"]["MONGO_PASSWORD"], "private-db-value")
        self.assertNotIn("MONGO_PASSWORD", services["llm"]["environment"])
        self.assertEqual(int(services["ollama"]["mem_limit"]), 6 * 1024 ** 3)
        self.assertNotIn("MONGO_PASSWORD", public_file.read_text())
        self.assertNotIn("DB_URL", public_file.read_text())

    @unittest.skipUnless(shutil.which("docker"), "requires Compose CLI, not Docker Engine")
    def test_compose_round_trip_preserves_dollars_quotes_backslashes_and_whitespace(self):
        version = subprocess.run([shutil.which("docker"), "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=30)
        if version.returncode:
            self.skipTest("Compose CLI is not available in this controller environment")
        environ = dict(self.environ, MONGO_PASSWORD="  $SHELL ${EXPAND} $$ ' \" \\end\\ пароль  ",
                       ADMIN_PASSWORD_HASH="pbkdf2-sha256$600000$quote's\\salt$hash")
        root = Path(self.directory.name)
        env_file = root / "generated.env"
        env_file.write_text(release.deployment_dotenv(environ), encoding="utf-8")
        secret_file = root / "secret.env"
        secret_file.write_text(release.deployment_secret_dotenv(environ), encoding="utf-8")
        compose = root / "compose.yml"
        compose.write_text('services:\n  check:\n    image: busybox\n    environment:\n      MONGO_PASSWORD: ${MONGO_PASSWORD}\n      ADMIN_PASSWORD_HASH: ${ADMIN_PASSWORD_HASH}\n', encoding="utf-8")
        process_env = {name: value for name, value in os.environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        result = subprocess.run([shutil.which("docker"), "compose", "-p", "release-env-test", "--env-file", str(env_file),
                                 "--env-file", str(secret_file),
                                 "-f", str(compose), "config", "--format", "json"],
                                capture_output=True, text=True, encoding="utf-8", env=process_env, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads(result.stdout)["services"]["check"]["environment"]
        # Compose config escapes dollars again for its reusable output. Compare with
        # values supplied directly by the process environment, bypassing dotenv parsing.
        process_env.update({name: environ[name] for name in ("MONGO_PASSWORD", "ADMIN_PASSWORD_HASH")})
        reference = subprocess.run(result.args, capture_output=True, text=True, encoding="utf-8", env=process_env, timeout=30)
        self.assertEqual(reference.returncode, 0, reference.stderr)
        expected = json.loads(reference.stdout)["services"]["check"]["environment"]
        self.assertEqual(actual, expected)

    def test_invalid_inputs_return_nonzero_without_printing_secret_values(self):
        self.environ["REGISTRY_USERNAME"] = ""
        output = io.StringIO()
        with redirect_stderr(output):
            self.assertEqual(release.main(self.argv, self.environ), 2)
        self.assertNotIn("private-registry-value", output.getvalue())


def subprocess_result(code):
    return argparse.Namespace(returncode=code)


if __name__ == "__main__":
    unittest.main()
