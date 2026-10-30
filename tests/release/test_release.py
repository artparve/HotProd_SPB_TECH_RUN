"""No SSH, Docker, real secrets or application services are used by these tests."""

import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
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
        base = "D:/Temp/opencode" if sys.platform == "win32" else None
        self.directory = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.environ = {
            "DEPLOY_HOST": "stand.example.org", "DEPLOY_USER": "deploy",
            "REGISTRY_USERNAME": "ci-user", "REGISTRY_PASSWORD": "private-registry-value",
            "DB_PASSWORD": "private-db-value",
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

    def test_dry_run_never_calls_ansible_and_never_prints_secrets(self):
        output = io.StringIO()
        with patch("scripts.release.subprocess.run") as run, redirect_stdout(output):
            self.assertEqual(release.main(self.argv + ["--dry-run"], self.environ), 0)
        run.assert_not_called()
        self.assertNotIn("private-", output.getvalue())

    def test_passes_inventory_as_json_and_preserves_ansible_exit_code(self):
        seen = []

        def run(command, **kwargs):
            inventory = json.loads(Path(command[2]).read_text())
            variables = json.loads(Path(command[-1][1:]).read_text())
            self.assertEqual(inventory["all"]["hosts"]["stand"]["ansible_user"], "deploy")
            env_file = Path(variables["release_env_file"])
            self.assertEqual(env_file.read_text(encoding="utf-8"), release.deployment_dotenv(self.environ))
            self.assertNotIn("private-db-value", json.dumps(variables))
            self.assertNotIn("DB_PASSWORD", kwargs["env"])
            self.assertEqual(kwargs["env"]["ANSIBLE_HOST_KEY_CHECKING"], "True")
            self.assertEqual(kwargs["env"]["ANSIBLE_NO_LOG"], "True")
            self.assertNotIn("shell", kwargs)
            self.assertNotIn("private-registry-value", " ".join(command))
            self.assertNotIn("private-db-value", " ".join(command))
            seen.append(Path(command[2]))
            seen.append(env_file)
            return subprocess_result(7)

        with patch("scripts.release.sys.platform", "linux"), patch("scripts.release.shutil.which", return_value="/bin/ansible-playbook"), patch("scripts.release.subprocess.run", side_effect=run), redirect_stdout(io.StringIO()):
            self.assertEqual(release.main(self.argv, self.environ), 7)
        self.assertFalse(seen[0].exists())
        self.assertFalse(seen[1].exists())

    def test_generates_only_allowlisted_settings_with_defaults(self):
        self.environ["UNRELATED_SECRET"] = "do-not-copy"
        dotenv = release.deployment_dotenv(self.environ)
        self.assertIn('DB_PASSWORD="private-db-value"\n', dotenv)
        self.assertIn('ADMIN_USERNAME=""\n', dotenv)
        self.assertIn('ADMIN_COOKIE_SECURE="true"\n', dotenv)
        self.assertIn('OLLAMA_NUM_THREADS="8"\n', dotenv)
        self.assertNotIn("do-not-copy", dotenv)
        self.assertNotIn("private-registry-value", dotenv)

    def test_required_password_and_multiline_values_fail_without_printing_secrets(self):
        for name, value in [("DB_PASSWORD", ""), ("DB_PASSWORD", "private-db-value\nINJECTED=value"),
                            ("ADMIN_PASSWORD_HASH", "private-value\r"), ("OLLAMA_URL", "private-value\x00")]:
            with self.subTest(name=name, value=value), patch.dict(self.environ, {name: value}):
                output = io.StringIO()
                with redirect_stderr(output):
                    self.assertEqual(release.main(self.argv + ["--dry-run"], self.environ), 2)
                self.assertNotIn("private-", output.getvalue())

    def test_workflow_has_an_individual_setting_for_every_runtime_variable(self):
        workflow = (release.ROOT / ".github/workflows/deploy.yml").read_text(encoding="utf-8")
        for name in release.DEPLOYMENT_ENV_DEFAULTS:
            self.assertRegex(workflow, rf"(?m)^\s+{name}:")
        self.assertIn("REGISTRY_PASSWORD: ${{ github.token }}", workflow)

    @unittest.skipUnless(shutil.which("docker"), "requires Compose CLI, not Docker Engine")
    def test_compose_round_trip_preserves_dollars_quotes_backslashes_and_whitespace(self):
        version = subprocess.run([shutil.which("docker"), "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=30)
        if version.returncode:
            self.skipTest("Compose CLI is not available in this controller environment")
        environ = dict(self.environ, DB_PASSWORD="  $SHELL ${EXPAND} $$ ' \" \\end\\ пароль  ",
                       ADMIN_PASSWORD_HASH="pbkdf2-sha256$600000$quote's\\salt$hash")
        root = Path(self.directory.name)
        env_file = root / "generated.env"
        env_file.write_text(release.deployment_dotenv(environ), encoding="utf-8")
        compose = root / "compose.yml"
        compose.write_text('services:\n  check:\n    image: busybox\n    environment:\n      DB_PASSWORD: ${DB_PASSWORD}\n      ADMIN_PASSWORD_HASH: ${ADMIN_PASSWORD_HASH}\n', encoding="utf-8")
        process_env = {name: value for name, value in os.environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        result = subprocess.run([shutil.which("docker"), "compose", "-p", "release-env-test", "--env-file", str(env_file),
                                 "-f", str(compose), "config", "--format", "json"],
                                capture_output=True, text=True, encoding="utf-8", env=process_env, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads(result.stdout)["services"]["check"]["environment"]
        # Compose config escapes dollars again for its reusable output. Compare with
        # values supplied directly by the process environment, bypassing dotenv parsing.
        process_env.update({name: environ[name] for name in ("DB_PASSWORD", "ADMIN_PASSWORD_HASH")})
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
