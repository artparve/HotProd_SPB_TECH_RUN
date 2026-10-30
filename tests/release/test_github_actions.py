"""Workflow helpers use process env; tests never contact GitHub or a deployment host."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import github_actions

try:
    import yaml
except ImportError:
    yaml = None


class GitHubActionsTests(unittest.TestCase):
    def setUp(self):
        base = None
        self.directory = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.directory.cleanup)
        self.environ = {
            "REPOSITORY": "Owner/RepairCalc", "RELEASE_SHA": "a" * 40,
            "DEPLOY_ENVIRONMENT": "staging", "GH_TOKEN": "private-api-token",
            "SSH_PRIVATE_KEY": "private-key\r\nsecond-line\r\n",
            "MONGO_PASSWORD": "private-db-password", "RUNNER_TEMP": self.directory.name,
        }

    def run_record(self, **overrides):
        record = {"head_sha": "a" * 40, "conclusion": "success", "head_branch": "main", "event": "push"}
        return dict(record, **overrides)

    def test_checks_successful_ci_for_exact_sha_before_deploy(self):
        response = io.BytesIO(json.dumps({"workflow_runs": [self.run_record()]}).encode())
        output = io.StringIO()
        with patch.object(github_actions, "urlopen", return_value=response) as fetch, redirect_stdout(output):
            self.assertEqual(github_actions.main(["check-release"], self.environ), 0)
        request = fetch.call_args.args[0]
        self.assertIn("head_sha=" + "a" * 40, request.full_url)
        self.assertIn("branch=main", request.full_url)
        self.assertEqual(request.get_header("Authorization"), "Bearer private-api-token")
        self.assertNotIn("private-api-token", output.getvalue())

    def test_rejects_failed_wrong_branch_event_or_sha_runs(self):
        runs = [self.run_record(conclusion="failure"), self.run_record(head_branch="feature"),
                self.run_record(event="pull_request"), self.run_record(head_sha="b" * 40)]
        response = io.BytesIO(json.dumps({"workflow_runs": runs}).encode())
        with patch.object(github_actions, "urlopen", return_value=response), redirect_stderr(io.StringIO()):
            self.assertEqual(github_actions.main(["check-release"], self.environ), 2)

    def test_invalid_inputs_do_not_call_github(self):
        for name, value in [("RELEASE_SHA", "latest;id"), ("REPOSITORY", "../other/repo"), ("GH_TOKEN", "")]:
            with self.subTest(name=name), patch.dict(self.environ, {name: value}), patch.object(github_actions, "urlopen") as fetch, redirect_stderr(io.StringIO()):
                self.assertEqual(github_actions.main(["check-release"], self.environ), 2)
                fetch.assert_not_called()

    def test_api_errors_never_expose_remote_response_or_token(self):
        error = HTTPError("https://api.github.com", 403, "private-api-token", {}, None)
        output = io.StringIO()
        with patch.object(github_actions, "urlopen", side_effect=error), redirect_stderr(output):
            self.assertEqual(github_actions.main(["check-release"], self.environ), 2)
        self.assertIn("403", output.getvalue())
        self.assertNotIn("private-api-token", output.getvalue())

    def test_deploy_creates_private_key_temporarily_and_preserves_release_exit_code(self):
        seen = []

        def run(argv, environ):
            self.assertEqual(argv, ["--environment", "staging", "--image-tag", "sha-" + "a" * 40,
                                    "--image-prefix", "ghcr.io/owner/repaircalc"])
            path = Path(environ["DEPLOY_SSH_KEY_FILE"])
            self.assertEqual(path.read_text(), "private-key\nsecond-line\n")
            if os.name == "posix":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("SSH_PRIVATE_KEY", environ)
            self.assertEqual(environ["MONGO_PASSWORD"], "private-db-password")
            seen.append(path)
            return 7

        with patch.object(github_actions.release, "main", side_effect=run):
            self.assertEqual(github_actions.main(["deploy"], self.environ), 7)
        self.assertFalse(seen[0].exists())

    def test_deploy_removes_private_key_even_if_release_raises(self):
        seen = []

        def fail(argv, environ):
            seen.append(Path(environ["DEPLOY_SSH_KEY_FILE"]))
            raise OSError("private-key")

        output = io.StringIO()
        with patch.object(github_actions.release, "main", side_effect=fail), redirect_stderr(output):
            self.assertEqual(github_actions.main(["deploy"], self.environ), 2)
        self.assertFalse(seen[0].exists())
        self.assertNotIn("private-key", output.getvalue())

    def test_missing_private_key_prevents_deployment(self):
        self.environ["SSH_PRIVATE_KEY"] = ""
        with patch.object(github_actions.release, "main") as run, redirect_stderr(io.StringIO()):
            self.assertEqual(github_actions.main(["deploy"], self.environ), 2)
        run.assert_not_called()

    def test_registry_output_uses_lowercase_namespace(self):
        path = Path(self.directory.name, "github-output")
        self.environ["GITHUB_OUTPUT"] = str(path)
        self.assertEqual(github_actions.main(["image-prefix"], self.environ), 0)
        self.assertEqual(path.read_text(), "prefix=ghcr.io/owner/repaircalc\n")

    def test_compose_validation_uses_only_synthetic_settings_and_cleans_file(self):
        seen = []

        def run(argv, **kwargs):
            public_file = Path(argv[3])
            self.assertNotIn("MONGO_PASSWORD=", public_file.read_text())
            self.assertNotIn("ADMIN_PASSWORD_HASH=", public_file.read_text())
            self.assertEqual(argv[4], "--env-file")
            file = Path(argv[5])
            self.assertTrue(all("ci-validation-only" in line for line in file.read_text().splitlines()))
            self.assertIn("MONGO_ROOT_PASSWORD=",file.read_text())
            self.assertEqual(kwargs["env"]["COMPOSE_PROFILES"], "")
            self.assertNotIn("MONGO_PASSWORD", kwargs["env"])
            self.assertNotIn("private-db-password", " ".join(argv))
            self.assertEqual(argv[-2:], ["config", "--quiet"])
            self.assertFalse(kwargs["check"])
            seen.append(file)
            seen.append(public_file)
            return type("Result", (), {"returncode": 3})()

        with patch.object(github_actions.subprocess, "run", side_effect=run):
            self.assertEqual(github_actions.main(["validate-compose"], self.environ), 3)
        self.assertFalse(seen[0].exists())
        self.assertFalse(seen[1].exists())


@unittest.skipUnless(yaml, "PyYAML is installed with the Ansible requirements")
class WorkflowTests(unittest.TestCase):
    def test_workflows_contain_commands_but_no_inline_programs(self):
        for path in (github_actions.ROOT / ".github/workflows").glob("*.yml"):
            workflow = yaml.safe_load(path.read_text())
            for job in workflow["jobs"].values():
                for step in job.get("steps", []):
                    command = step.get("run", "")
                    self.assertNotIn("\n", command, f"Inline script in {path}: {step.get('name', command)}")
                    self.assertNotIn("${{", command, "Scripts must receive inputs through process env")
                    self.assertNotRegex(command, r"(?:<<|\becho\b|\bprintf\b|python(?:3)?\s+-c)")

    def test_deployment_settings_are_bound_to_selected_environment_and_exported_to_process_env(self):
        path = github_actions.ROOT / ".github/workflows/deploy.yml"
        job = yaml.safe_load(path.read_text())["jobs"]["deploy"]
        self.assertEqual(job["if"], "github.ref == 'refs/heads/main'")
        self.assertEqual(job["steps"][0]["with"]["ref"], "main")
        self.assertEqual(job["environment"]["name"], "${{ inputs.environment }}")
        self.assertEqual(job["env"]["DEPLOY_HOST"], "${{ vars.DEPLOY_HOST }}")
        self.assertEqual(job["env"]["DEPLOY_FRONTEND_HOST"], "${{ vars.DEPLOY_FRONTEND_HOST || '0.0.0.0' }}")
        step = next(step for step in job["steps"] if step.get("run") == "python -m scripts.github_actions deploy")
        self.assertEqual(step["env"]["SSH_PRIVATE_KEY"], "${{ secrets.DEPLOY_SSH_PRIVATE_KEY }}")
        self.assertEqual(step["env"]["MONGO_PASSWORD"], "${{ secrets.MONGO_PASSWORD }}")
        self.assertEqual(step["env"]["REGISTRY_PASSWORD"], "${{ github.token }}")
        exported = {**job["env"], **step["env"]}
        self.assertTrue(set(github_actions.release.DEPLOYMENT_ENV_DEFAULTS) <= set(exported))


if __name__ == "__main__":
    unittest.main()
