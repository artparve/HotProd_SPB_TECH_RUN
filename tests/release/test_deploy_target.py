import argparse
from copy import deepcopy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import deploy_target


def container(project, name, running=True):
    return {"Id": project + "-" + name,
            "Config": {"Labels": {"com.docker.compose.project": project, "com.docker.compose.service": name},
                       "Env": ["POSTGRES_USER=repaircalc", "POSTGRES_DB=repaircalc"]},
            "State": {"Status": "running" if running else "created", "Health": {"Status": "healthy"}},
            "HostConfig": {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}]} if name == "frontend" else {}},
            "NetworkSettings": {"Networks": {project + "_default": {"IPAddress": "172.18.0.2"}}},
            "Mounts": [{"Type": "volume", "Name": project + "_pgdata", "Destination": "/var/lib/postgresql/data"}] if name == "postgres" else []}


class DeploymentTargetTests(unittest.TestCase):
    def setUp(self):
        base = "D:/Temp/opencode" if sys.platform == "win32" else None
        self.directory = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.args = argparse.Namespace(path=root / "release", base=root, project="repaircalc_prod",
                                       environment="prod", host="127.0.0.1", port=8080)
        self.containers = [container("repaircalc", name) for name in ("frontend", "backend", "postgres")]
        self.config = {"services": {"postgres": {"image": "postgres:17.11", "environment": {
            "POSTGRES_USER": "repaircalc", "POSTGRES_DB": "repaircalc", "POSTGRES_PASSWORD": "private-password"}},
            "backend": {"environment": {}}}}

    def test_adopts_live_legacy_even_when_failed_new_stack_already_exists(self):
        containers = self.containers + [container("repaircalc_prod", name, name != "frontend")
                                        for name in ("frontend", "backend", "postgres")]
        self.assertEqual(deploy_target.choose(containers, "repaircalc_prod", "prod", "0.0.0.0", 8080),
                         ("repaircalc", True, True))

    def test_never_evicts_another_service_project_or_environment(self):
        for project, environment, source in (("other", "prod", "repaircalc_prod"),
                                              ("repaircalc", "staging", "repaircalc_staging")):
            with self.subTest(project=project):
                containers = [container(project, name) for name in ("frontend", "backend", "postgres")]
                with self.assertRaises(deploy_target.PreflightError):
                    deploy_target.choose(containers, source, environment, "127.0.0.1", 8080)
        occupied = container("unrelated", "database")
        occupied["HostConfig"] = self.containers[0]["HostConfig"]
        with self.assertRaises(deploy_target.PreflightError):
            deploy_target.choose([occupied], "repaircalc_prod", "prod", "127.0.0.1", 8080)

    def test_unhealthy_or_missing_legacy_service_refuses_adoption(self):
        for containers in (self.containers[:2], [container("repaircalc", name, name != "backend")
                                                for name in ("frontend", "backend", "postgres")]):
            with self.assertRaises(deploy_target.PreflightError):
                deploy_target.choose(containers, "repaircalc_prod", "prod", "127.0.0.1", 8080)

    def test_pinned_project_wins_even_if_frontend_temporarily_stopped(self):
        self.containers[0]["State"]["Status"] = "exited"
        pin = {"project": "repaircalc", "volume": "repaircalc_pgdata"}
        self.assertEqual(deploy_target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8080, pin),
                         ("repaircalc", False, False))

    def test_do_not_silently_switch_db_by_selecting_a_different_port_or_managed_project(self):
        with self.assertRaises(deploy_target.PreflightError):
            deploy_target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8081)
        with self.assertRaises(deploy_target.PreflightError):
            deploy_target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8080, managed=True)

    def test_do_not_change_a_pinned_project_to_the_current_port_owner(self):
        pin = {"project": "repaircalc_prod", "volume": "repaircalc_prod_pgdata"}
        with self.assertRaises(deploy_target.PreflightError):
            deploy_target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8080, pin)

    def run_plan(self, **kwargs):
        def run(argv, data=None, **_):
            if argv[:3] == ["docker", "ps", "-aq"]:
                return "\n".join(c["Id"] for c in self.containers)
            if argv[:2] == ["docker", "inspect"]:
                return json.dumps(self.containers)
            if "config" in argv:
                return json.dumps(self.config)
            raise AssertionError("Unexpected command in read-only plan")
        with patch.object(deploy_target, "command", side_effect=run), patch.object(deploy_target, "query", **kwargs):
            return deploy_target.plan(self.args)

    def test_initialized_db_keeps_volume_and_skips_sample_bootstrap(self):
        result = self.run_plan(side_effect=["170011", "t", "t"])
        self.assertEqual(result["project"], "repaircalc")
        self.assertEqual(result["volume"], "repaircalc_pgdata")
        self.assertTrue(result["preserve_catalog"])
        self.assertTrue(result["existing_database"])
        self.assertNotIn("private-", json.dumps(result))

    def test_new_or_empty_database_still_bootstraps(self):
        result = self.run_plan(side_effect=["170011", "f"])
        self.assertFalse(result["preserve_catalog"])
        with patch.object(deploy_target, "command", side_effect=["", json.dumps(self.config), ""]), patch.object(deploy_target.socket, "socket"):
            result = deploy_target.plan(self.args)
        self.assertFalse(result["existing_database"])
        self.assertEqual(result["project"], "repaircalc_prod")

    def test_reject_changed_database_identity_major_version_volume_or_disabled_admin(self):
        changes = [lambda: self.config["services"]["postgres"]["environment"].update(POSTGRES_DB="other"),
                   lambda: self.config["services"]["postgres"].update(image="postgres:18.0"),
                   lambda: self.containers[2]["Mounts"][0].update(Name="other_pgdata"),
                   lambda: self.containers[1]["Config"]["Env"].extend(["ADMIN_USERNAME=admin", "ADMIN_PASSWORD_HASH=private-hash"])]
        original_config, original_containers = deepcopy(self.config), deepcopy(self.containers)
        for change in changes:
            with self.subTest(change=change):
                self.config, self.containers = deepcopy(original_config), deepcopy(original_containers)
                change()
                with self.assertRaises(deploy_target.PreflightError) as caught:
                    self.run_plan(side_effect=["170011", "t", "t"])
                self.assertNotIn("private-", str(caught.exception))

    def test_missing_existing_container_or_unavailable_port_does_not_create_new_db(self):
        with patch.object(deploy_target, "command", side_effect=["", json.dumps(self.config), "repaircalc_prod_pgdata\n"]), patch.object(deploy_target.socket, "socket"):
            with self.assertRaises(deploy_target.PreflightError):
                deploy_target.plan(self.args)
        with patch.object(deploy_target, "command", return_value=""), patch.object(deploy_target.socket, "socket", side_effect=OSError):
            with self.assertRaises(deploy_target.PreflightError):
                deploy_target.plan(self.args)

    def test_database_password_is_stdin_only_and_errors_do_not_echo_secrets(self):
        postgres = self.containers[2]
        settings = self.config["services"]["postgres"]["environment"]
        with patch.object(deploy_target, "command", return_value="1\n") as run:
            self.assertEqual(deploy_target.query(postgres, settings, "SELECT 1"), "1")
        self.assertNotIn("private-password", " ".join(run.call_args.args[0]))
        self.assertEqual(run.call_args.args[1], "private-password\n")
        self.assertIn("172.18.0.2", run.call_args.args[0])
        self.assertNotIn("127.0.0.1", " ".join(run.call_args.args[0]))
        self.assertIn('PGCONNECT_TIMEOUT=5', " ".join(run.call_args.args[0]))
        result = subprocess.CompletedProcess([], 1, "", "private-password")
        with patch.object(deploy_target.subprocess, "run", return_value=result):
            with self.assertRaises(deploy_target.PreflightError) as caught:
                deploy_target.query(postgres, settings, "SELECT 1")
        self.assertNotIn("private-password", str(caught.exception))

    def test_network_password_failure_is_not_masked_by_localhost_trust(self):
        postgres = self.containers[2]
        settings = self.config["services"]["postgres"]["environment"]

        def run(argv, data=None):
            # Simulate the official image: localhost would accept even the wrong password.
            if "127.0.0.1" in " ".join(argv):
                return "170011\n"
            raise deploy_target.PreflightError("password authentication failed: private-password")

        with patch.object(deploy_target, "command", side_effect=run):
            with self.assertRaisesRegex(deploy_target.PreflightError, "Docker network") as caught:
                deploy_target.query(postgres, settings, "SHOW server_version_num")
        self.assertNotIn("private-password", str(caught.exception))

    def test_missing_bridge_address_never_falls_back_to_localhost(self):
        for network in ({}, {"IPAddress": "127.0.0.1"}, {"IPAddress": "0.0.0.0"}, {"IPAddress": "invalid"}):
            with self.subTest(network=network):
                postgres = deepcopy(self.containers[2])
                postgres["NetworkSettings"]["Networks"]["repaircalc_default"] = network
                with patch.object(deploy_target, "command") as run:
                    with self.assertRaises(deploy_target.PreflightError):
                        deploy_target.query(postgres, self.config["services"]["postgres"]["environment"], "SELECT 1")
                run.assert_not_called()

    def test_ipv6_bridge_address_is_supported(self):
        postgres = deepcopy(self.containers[2])
        postgres["NetworkSettings"]["Networks"]["repaircalc_default"] = {"IPAddress": "", "GlobalIPv6Address": "fd00::2"}
        self.assertEqual(deploy_target.database_address(postgres), "fd00::2")

    def test_invalid_remote_output_fails_without_leaking_it(self):
        output = io.StringIO()
        with patch.object(deploy_target, "command", side_effect=ValueError("private-password")), redirect_stdout(output):
            rc = deploy_target.main(["plan", "--path", str(self.args.path), "--base", str(self.args.base),
                                     "--project", "repaircalc_prod", "--environment", "prod", "--host", "127.0.0.1", "--port", "8080"])
        self.assertEqual(rc, 1)
        self.assertNotIn("private-password", output.getvalue())

    def prepare_backup(self):
        self.args.path.mkdir()
        saved = {"project": "repaircalc", "volume": "repaircalc_pgdata", "postgres_id": self.containers[2]["Id"]}
        (self.args.path / "deployment-target.json").write_text(json.dumps(saved))

    def test_backup_is_private_binary_archive_without_secrets_in_argv(self):
        self.prepare_backup()

        def run(argv, **kwargs):
            self.assertNotIn("private-password", " ".join(argv))
            if "pg_restore" in argv:
                self.assertEqual(kwargs["stdin"].read(), b"PGDMP-binary-content")
            else:
                kwargs["stdout"].write(b"PGDMP-binary-content")
            return subprocess.CompletedProcess(argv, 0, None, b"")

        with patch.object(deploy_target, "command", return_value=json.dumps([self.containers[2]])), patch.object(deploy_target.subprocess, "run", side_effect=run):
            result = deploy_target.backup(self.args)
        target = Path(result["backup"])
        self.assertEqual(target.read_bytes(), b"PGDMP-binary-content")
        self.assertFalse((self.args.path / "database-before.dump.partial").exists())
        if sys.platform != "win32":
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_backup_failure_removes_partial_file_and_keeps_existing_backup(self):
        self.prepare_backup()
        target = self.args.path / "database-before.dump"
        target.write_bytes(b"previous-backup")
        result = subprocess.CompletedProcess([], 1, None, b"private-password")
        with patch.object(deploy_target, "command", return_value=json.dumps([self.containers[2]])), patch.object(deploy_target.subprocess, "run", return_value=result):
            with self.assertRaises(deploy_target.PreflightError) as caught:
                deploy_target.backup(self.args)
        self.assertNotIn("private-password", str(caught.exception))
        self.assertEqual(target.read_bytes(), b"previous-backup")
        self.assertFalse((self.args.path / "database-before.dump.partial").exists())


if __name__ == "__main__":
    unittest.main()
