import unittest
import json
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch
import yaml
from scripts import mongo_runtime


class MongoImageConfigurationTests(unittest.TestCase):
    def test_default_is_pinned_and_matches_compose_and_cd(self):
        tag = mongo_runtime.default_image_tag()
        self.assertRegex(tag, r"^8\.0\.\d+@sha256:[a-f0-9]{64}$")
        self.assertEqual(mongo_runtime.image_reference({}), "mongo:" + tag)
        root = Path(__file__).resolve().parents[2]
        compose = yaml.safe_load((root / "docker/docker-compose.yml").read_text())
        for name in ("mongo", "mongo-init"):
            self.assertEqual(compose["services"][name]["image"], "mongo:${MONGO_IMAGE_TAG:-" + tag + "}")
        cd = yaml.safe_load((root / ".github/workflows/cd.yml").read_text())
        self.assertEqual(cd["jobs"]["deploy"]["env"]["MONGO_IMAGE_TAG"], "${{ vars.MONGO_IMAGE_TAG || '" + tag + "' }}")

    def test_explicit_override_and_invalid_tags(self):
        self.assertEqual(mongo_runtime.image_reference({"MONGO_IMAGE_TAG": "8.0.34"}), "mongo:8.0.34")
        for tag in ("8.0\nother=value", "bad tag", "8.0@sha256:invalid"):
            with self.assertRaises(ValueError):
                mongo_runtime.image_reference({"MONGO_IMAGE_TAG": tag})

    def test_mongo_jobs_use_compatible_runner_and_release_gate_stays_on_main(self):
        root = Path(__file__).resolve().parents[2]
        ci = yaml.safe_load((root / '.github/workflows/ci.yml').read_text())
        self.assertTrue(all(ci['jobs'][name]['runs-on'] == 'ubuntu-22.04' for name in ('backend', 'stack')))
        cd = yaml.safe_load((root / '.github/workflows/cd.yml').read_text())
        self.assertEqual(cd['jobs']['deploy']['if'], "github.ref == 'refs/heads/main'")


class ProbeTests(unittest.TestCase):
    def exercise(self, mode='healthy', remote=False, kernel='6.8.0'):
        self.calls = []
        self.elapsed = 0.0
        self.token = ''
        diagnostics = {'daemon': {'kernel': kernel}, 'image': 'mongo:8.0.34', 'image_details': {'id': 'test-image'}}

        def run(argv, timeout=20):
            self.calls.append(argv)
            rc, out = 0, ''
            if argv[:2] == ['docker', 'run']:
                self.token = argv[argv.index('--label') + 1].split('=', 1)[1]
                out = 'probe-container'
            elif 'inspect' in argv:
                if 'compatibility-probe' in argv[-2]:
                    out = self.token if mode != 'foreign-owner' else 'another-owner'
                else:
                    dead = mode == 'kernel-exit' or (mode == 'late-crash' and self.elapsed > 60)
                    out = json.dumps({'state': {'Status': 'exited' if dead else 'running', 'ExitCode': 1 if dead else 0, 'OOMKilled': False}, 'restarts': 0})
            elif argv[:2] == ['docker', 'exec']:
                if 'cat' in argv:
                    out = 'Ubuntu test signature'
                elif mode == 'timeout':
                    rc = 1
                elif mode == 'bad-transaction' and argv[-1] == mongo_runtime.TRANSACTION:
                    rc = 1
            elif argv[:2] == ['docker', 'logs']:
                out = 'MongoDB cannot start: Linux kernel 12257600' if mode == 'kernel-exit' else 'probe output'
            return subprocess.CompletedProcess(argv, rc, out, '')

        def sleep(seconds):
            self.elapsed += seconds

        with patch.object(mongo_runtime, 'diagnostics', return_value=diagnostics), patch.object(mongo_runtime, 'run', side_effect=run), patch.object(mongo_runtime.time, 'monotonic', side_effect=lambda: self.elapsed), patch.object(mongo_runtime.time, 'sleep', side_effect=sleep):
            return mongo_runtime.check('mongo:8.0.34', remote=remote, seconds=95, startup_timeout=10)

    def test_real_transaction_and_long_lived_probe_contract(self):
        report = self.exercise()
        self.assertEqual(report['status'], 'ok')
        self.assertTrue(report['transaction_verified'])
        self.assertGreaterEqual(self.elapsed, 95)
        command = next(c for c in self.calls if c[:2] == ['docker', 'run'])
        self.assertEqual(command[command.index('--network') + 1], 'none')
        self.assertEqual(command.count('--tmpfs'), 3)
        self.assertNotIn('--publish', command)
        self.assertNotIn('--env', command)
        removed = next(c for c in self.calls if c[:2] == ['docker', 'rm'])
        self.assertTrue(removed[-1].startswith('repaircalc_mongo_compat_'))

    def test_kernel_exit_and_readiness_timeout_are_distinct(self):
        self.assertEqual(self.exercise('kernel-exit')['error_code'], 'mongodb_kernel_incompatible')
        self.assertEqual(self.exercise('timeout')['error_code'], 'mongodb_startup_timeout')

    def test_late_crash_or_transaction_failure_cannot_pass(self):
        self.assertEqual(self.exercise('late-crash')['status'], 'error')
        self.assertEqual(self.exercise('bad-transaction')['error_code'], 'mongodb_transaction_failed')

    def test_only_the_owned_probe_is_removed(self):
        report = self.exercise('foreign-owner')
        self.assertEqual(report['error_code'], 'mongodb_probe_cleanup_unverified')
        self.assertFalse(any(c[:2] == ['docker', 'rm'] for c in self.calls))

    def test_remote_check_uses_daemon_kernel_and_probes_suspect_kernels(self):
        report = self.exercise(remote=True, kernel='6.8.0-azure')
        self.assertEqual(report['status'], 'ok')
        self.assertFalse(report['probe_performed'])
        self.assertEqual(self.calls, [])
        self.assertTrue(self.exercise(remote=True, kernel='7.0.0-azure')['probe_performed'])

    def test_too_short_probe_is_rejected(self):
        with self.assertRaises(ValueError):
            mongo_runtime.check('mongo:8.0.34', seconds=90)

    def test_reports_are_private_and_diagnostics_do_not_read_config_env(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.json'
            mongo_runtime.write_report(report, {'status': 'ok'})
            self.assertEqual(json.loads(report.read_text()), {'status': 'ok'})
        with patch.object(mongo_runtime, 'run', return_value=subprocess.CompletedProcess([], 0, '{}', '')) as run:
            mongo_runtime.diagnostics('mongo:8.0.34')
        self.assertTrue(all('.Config' not in ' '.join(call.args[0]) for call in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
