import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import deploy_diagnostics


class DeploymentDiagnosticsTests(unittest.TestCase):
    def test_compose_json_formats(self):
        items = [{"Service": "backend"}, {"Service": "frontend"}]
        self.assertEqual(deploy_diagnostics.rows(json.dumps(items)), items)
        self.assertEqual(deploy_diagnostics.rows("\n".join(json.dumps(item) for item in items)), items)
        self.assertEqual(deploy_diagnostics.rows("not JSON"), [])

    def test_only_safe_status_is_printed_and_raw_logs_stay_in_private_file(self):
        base = None
        with tempfile.TemporaryDirectory(dir=base) as directory:
            root = Path(directory)
            (root / "deployment-failure.json").write_text(json.dumps({"rc": 1, "stderr": "port is already allocated; private-token"}))
            commands = []

            def run(argv):
                commands.append(argv)
                if "ps" in argv:
                    text = json.dumps([{"Service": "backend", "ID": "a" * 64, "State": "exited"},
                                       {"Service": "init-data", "ID": "b" * 64, "State": "exited"}])
                elif "inspect" in argv:
                    text = json.dumps({"Status": "exited", "ExitCode": 137, "OOMKilled": True,
                                       "Error": "private-token", "Health": {"Status": "unhealthy", "Log": ["private-token"]}})
                else:
                    text = "password authentication failed: private-password"
                return {"rc": 0, "stdout": text, "stderr": ""}

            output = io.StringIO()
            with patch.object(deploy_diagnostics, "run", side_effect=run), redirect_stdout(output):
                self.assertEqual(deploy_diagnostics.main(["--release-path", str(root), "--project", "repaircalc_staging"]), 0)
            summary = json.loads(output.getvalue())
            self.assertEqual(summary["failure_rc"], 1)
            self.assertIn("port_conflict", summary["signals"])
            self.assertIn("oom_killed", summary["signals"])
            self.assertIn("bootstrap_failed", summary["signals"])
            self.assertIn("database_authentication_failed", summary["signals"])
            self.assertNotIn("private-", output.getvalue())
            report = root / "deployment-diagnostics.json"
            self.assertIn("private-password", report.read_text())
            if os.name == "posix":
                self.assertEqual(report.stat().st_mode & 0o777, 0o600)
            for command in commands:
                if "logs" in command:
                    self.assertEqual(command[-4:-1], ["--no-color", "--tail", "80"])
                if "inspect" in command:
                    self.assertIn("{{json .State}}", command)
                    self.assertNotIn(".Config", " ".join(command))

    def test_bounded_command_output(self):
        result = type("Result", (), {"returncode": 23, "stdout": "x" * 100000, "stderr": "y" * 100000})()
        with patch.object(deploy_diagnostics.subprocess, "run", return_value=result) as run:
            actual = deploy_diagnostics.run(["docker", "compose", "ps"])
        self.assertEqual(actual["rc"], 23)
        self.assertEqual(len(actual["stdout"]), deploy_diagnostics.LIMIT)
        self.assertEqual(len(actual["stderr"]), deploy_diagnostics.LIMIT)
        self.assertEqual(run.call_args.kwargs["timeout"], 20)


if __name__ == "__main__":
    unittest.main()
