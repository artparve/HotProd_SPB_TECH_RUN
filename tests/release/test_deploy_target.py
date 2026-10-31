import argparse
from copy import deepcopy
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts import deploy_target as target


def container(project, name, running=True):
    return {"Id": project + "-" + name,
            "Config": {"Labels": {"com.docker.compose.project": project, "com.docker.compose.service": name},
                       "Env": ["MONGO_USER=repaircalc", "MONGO_URI=mongodb://mongo:27017/repaircalc"]},
            "State": {"Status": "running" if running else "created", "Health": {"Status": "healthy"}},
            "HostConfig": {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}]} if name == "frontend" else {}},
            "NetworkSettings": {"Networks": {project + "_default": {"IPAddress": "172.18.0.2"}}},
            "Mounts": [{"Type": "volume", "Name": project + "_mongo-data", "Destination": "/data/db"}] if name == "mongo" else []}


class DeploymentTargetTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.args = argparse.Namespace(path=root / "release", base=root, project="repaircalc_prod",
                                       environment="prod", host="127.0.0.1", port=8080)
        self.containers = [container("repaircalc", name) for name in ("frontend", "backend", "mongo")]
        self.config = {"services": {"mongo": {"image": "mongo:8.0", "environment": {
            "MONGO_INITDB_ROOT_USERNAME": "root", "MONGO_INITDB_ROOT_PASSWORD": "private-root",
            "MONGO_PORT": "27017", "MONGO_REPLICA_SET": "rs0"}}, "backend": {"environment": {
            "MONGO_USER": "repaircalc", "MONGO_URI": "mongodb://mongo:27017/repaircalc", "MONGO_PASSWORD": "private-password"}}}}
        self.state = {"version": "8.0.15", "replicaSet": "rs0", "primary": True, "initialized": True, "invalidPointer": False}

    def run_plan(self):
        def run(argv, *args, **kwargs):
            if argv[:3] == ["docker", "ps", "-aq"]:
                return "\n".join(c["Id"] for c in self.containers)
            if argv[:2] == ["docker", "inspect"]:
                return json.dumps(self.containers)
            if "config" in argv:
                return json.dumps(self.config)
            if "volume" in argv:
                return ""
            raise AssertionError("Unexpected mutation in read-only preflight")
        with patch.object(target, "command", side_effect=run), patch.object(target, "query", return_value=self.state), patch.object(target.socket, "socket"), patch.object(target, "runtime_check", return_value={"status": "ok", "daemon": {"kernel": "6.8.0"}, "probe_performed": False}):
            return target.plan(self.args)

    def test_compatibility_failure_is_safe_and_uses_the_selected_image(self):
        result = subprocess.CompletedProcess([], 1, json.dumps({'status': 'error', 'error_code': 'mongodb_kernel_incompatible'}), 'private-password')
        with patch.object(target.subprocess, 'run', return_value=result) as run:
            with self.assertRaisesRegex(target.PreflightError, 'mongodb_kernel_incompatible') as caught:
                target.runtime_check(self.args.path, self.config)
        self.assertNotIn('private-', str(caught.exception))
        argv = run.call_args.args[0]
        self.assertEqual(argv[argv.index('--image') + 1], self.config['services']['mongo']['image'])
        self.assertNotIn('private-', ' '.join(argv))

    def test_invalid_compatibility_report_does_not_pass(self):
        for response in ('not-json', '[]', '{"status":"error","error_code":"private-password"}'):
            with patch.object(target.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, response, '')):
                with self.assertRaises(target.PreflightError) as caught:
                    target.runtime_check(self.args.path, self.config)
                self.assertNotIn('private-', str(caught.exception))

    def test_adopts_existing_healthy_mongo_without_switching_volume(self):
        result = self.run_plan()
        self.assertEqual(result["project"], "repaircalc")
        self.assertEqual(result["volume"], "repaircalc_mongo-data")
        self.assertTrue(result["preserve_catalog"])
        self.assertTrue(result["existing_database"])
        self.assertNotIn("private-", json.dumps(result))

    def test_never_evicts_another_service_project_or_environment(self):
        for project, environment, source in (("other", "prod", "repaircalc_prod"), ("repaircalc", "staging", "repaircalc_staging")):
            with self.assertRaises(target.PreflightError):
                target.choose([container(project, n) for n in ("frontend", "backend", "mongo")], source, environment, "127.0.0.1", 8080)
        occupied = container("other", "database")
        occupied["HostConfig"] = self.containers[0]["HostConfig"]
        with self.assertRaises(target.PreflightError):
            target.choose([occupied], "repaircalc_prod", "prod", "127.0.0.1", 8080)

    def test_unhealthy_or_missing_service_refuses_adoption(self):
        for items in (self.containers[:2], [container("repaircalc", n, n != "backend") for n in ("frontend", "backend", "mongo")]):
            with self.assertRaises(target.PreflightError):
                target.choose(items, "repaircalc_prod", "prod", "127.0.0.1", 8080)

    def test_pinned_project_survives_temporarily_stopped_frontend(self):
        self.containers[0]["State"]["Status"] = "exited"
        pin = {"project": "repaircalc", "volume": "repaircalc_mongo-data"}
        self.assertEqual(target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8080, pin), ("repaircalc", False, False))
        with self.assertRaises(target.PreflightError):
            target.choose(self.containers, "repaircalc_prod", "prod", "127.0.0.1", 8081)

    def test_postgres_stack_is_never_adopted_or_converted(self):
        self.containers[2] = container("repaircalc", "postgres")
        with self.assertRaisesRegex(target.PreflightError, "PostgreSQL"):
            self.run_plan()
        self.containers[0]["State"]["Status"] = "exited"
        # A new project on a free port may coexist with untouched PostgreSQL.
        self.assertFalse(self.run_plan()["existing_database"])
        self.args.project = "repaircalc"
        with self.assertRaisesRegex(target.PreflightError, "PostgreSQL"):
            self.run_plan()

    def test_empty_database_still_bootstraps(self):
        self.state["initialized"] = False
        self.assertFalse(self.run_plan()["preserve_catalog"])
        self.containers = []
        self.assertFalse(self.run_plan()["existing_database"])

    def test_database_identity_major_version_volume_and_admin_cannot_change_silently(self):
        original_config, original_containers = deepcopy(self.config), deepcopy(self.containers)
        changes = [lambda: self.config["services"]["backend"]["environment"].update(MONGO_URI="mongodb://mongo:27017/other"),
                   lambda: self.config["services"]["mongo"].update(image="mongo:9.0"),
                   lambda: self.containers[2]["Mounts"][0].update(Name="other_mongo-data"),
                   lambda: self.containers[1]["Config"]["Env"].extend(["ADMIN_USERNAME=admin", "ADMIN_PASSWORD_HASH=private-hash"])]
        for change in changes:
            self.config, self.containers = deepcopy(original_config), deepcopy(original_containers)
            change()
            with self.assertRaises(target.PreflightError) as caught:
                self.run_plan()
            self.assertNotIn("private-", str(caught.exception))

    def test_invalid_publication_or_replica_set_is_not_reseeded(self):
        for key, value in (("primary", False), ("replicaSet", "other"), ("invalidPointer", True)):
            original = deepcopy(self.state)
            self.state[key] = value
            with self.assertRaises(target.PreflightError):
                self.run_plan()
            self.state = original

    def test_orphan_volume_or_occupied_host_port_refuses_new_database(self):
        with patch.object(target, "command", side_effect=["", json.dumps(self.config), "repaircalc_prod_mongo-data\n"]), patch.object(target.socket, "socket"):
            with self.assertRaises(target.PreflightError):
                target.plan(self.args)
        with patch.object(target, "command", return_value=""), patch.object(target.socket, "socket", side_effect=OSError):
            with self.assertRaises(target.PreflightError):
                target.plan(self.args)

    def test_credentials_are_only_stdin_and_network_authentication_failures_are_safe(self):
        settings = {"MONGO_PASSWORD": "private-password"}
        with patch.object(target, "command", return_value='{"ok":1}') as run:
            self.assertEqual(target.query(self.containers[2], settings, "auth"), {"ok": 1})
        self.assertNotIn("private-password", " ".join(run.call_args.args[0]))
        payload = json.loads(run.call_args.args[1])
        self.assertEqual(payload["host"], "172.18.0.2")
        self.assertEqual(payload["settings"]["MONGO_PASSWORD"], "private-password")
        with patch.object(target, "command", side_effect=target.PreflightError("private-password")):
            with self.assertRaisesRegex(target.PreflightError, "Docker network") as caught:
                target.query(self.containers[2], settings, "auth")
        self.assertNotIn("private-password", str(caught.exception))

    def test_missing_bridge_address_never_uses_localhost(self):
        for network in ({}, {"IPAddress": "127.0.0.1"}, {"IPAddress": "0.0.0.0"}):
            self.containers[2]["NetworkSettings"]["Networks"]["repaircalc_default"] = network
            with self.assertRaises(target.PreflightError):
                target.query(self.containers[2], {}, "auth")

    def prepare_backup(self):
        self.args.path.mkdir()
        saved = {"project": "repaircalc", "volume": "repaircalc_mongo-data", "mongo_id": self.containers[2]["Id"], "database": "repaircalc"}
        (self.args.path / "deployment-target.json").write_text(json.dumps(saved))

    def test_backup_delegates_to_consistent_mongo_archive_utility(self):
        self.prepare_backup()
        with patch.object(target, "command", side_effect=[json.dumps([self.containers[2]]), ""]) as run:
            result = target.backup(self.args)
        argv = run.call_args.args[0]
        self.assertIn(str(self.args.path / "scripts/mongo-backup.py"), argv)
        self.assertTrue(result["backup"].endswith("database-before.archive.gz"))
        self.assertNotIn("private-", " ".join(argv))

    def test_backup_never_overwrites_existing_archive(self):
        self.prepare_backup()
        archive = self.args.path / "database-before.archive.gz"
        archive.write_bytes(b"previous")
        with patch.object(target, "command", return_value=json.dumps([self.containers[2]])):
            with self.assertRaises(target.PreflightError):
                target.backup(self.args)
        self.assertEqual(archive.read_bytes(), b"previous")

    def test_backup_failure_does_not_expose_raw_errors(self):
        self.prepare_backup()
        with patch.object(target, "command", side_effect=[json.dumps([self.containers[2]]), target.PreflightError("Backup failed")]):
            with self.assertRaises(target.PreflightError):
                target.backup(self.args)


if __name__ == "__main__":
    unittest.main()
