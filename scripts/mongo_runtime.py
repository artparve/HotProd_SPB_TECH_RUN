"""Mongo image selection, safe diagnostics and an isolated compatibility probe.

The probe has no network, host ports, credentials or persistent volumes. Only its
own labelled container is removed. Remote checks use the Docker daemon's kernel.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def default_image_tag():
    for line in (ROOT / "env/.env.example").read_text(encoding="utf-8").splitlines():
        if line.startswith("MONGO_IMAGE_TAG="):
            return line.split("=", 1)[1].strip()
    raise ValueError("MONGO_IMAGE_TAG is missing from tracked defaults")


def image_reference(environ=None):
    env = os.environ if environ is None else environ
    tag = env.get("MONGO_IMAGE_TAG") or default_image_tag()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}(?:@sha256:[0-9a-f]{64})?", tag):
        raise ValueError("Invalid MONGO_IMAGE_TAG")
    return "mongo:" + tag


def run(argv, timeout=20):
    try:
        return subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(argv, 124, "", "")


def json_command(argv):
    result = run(argv)
    try:
        return json.loads(result.stdout) if result.returncode == 0 else None
    except ValueError:
        return None


def diagnostics(image):
    info = json_command(["docker", "info", "--format",
                         '{"kernel":{{json .KernelVersion}},"architecture":{{json .Architecture}},"version":{{json .ServerVersion}}}'])
    signature = Path("/proc/version_signature")
    result = {"image": image, "daemon": info, "client_kernel": platform.release(),
              "client_kernel_signature": signature.read_text().strip()[:500] if signature.is_file() else None,
              "runner": {key: os.environ.get(key) for key in ("ImageOS", "ImageVersion", "RUNNER_OS", "RUNNER_ARCH")}}
    compose = run(["docker", "compose", "version", "--short"])
    result["compose_version"] = compose.stdout.strip()[:100] if compose.returncode == 0 else None
    result["image_details"] = json_command(["docker", "image", "inspect", "--format",
                                            '{"id":{{json .Id}},"digests":{{json .RepoDigests}},"architecture":{{json .Architecture}}}', image])
    return result


def classify_failure(logs, running=False):
    text = logs.lower()
    if "12257600" in text or ("mongodb cannot start" in text and "kernel" in text):
        return "mongodb_kernel_incompatible"
    return "mongodb_startup_timeout" if running else "mongodb_process_exited"


class ProbeFailure(Exception):
    def __init__(self, code):
        self.code = code


STATE_FORMAT = '{"state":{{json .State}},"restarts":{{.RestartCount}}}'
TRANSACTION = '''
const s=db.getMongo().startSession(); const d=s.getDatabase('repaircalc_compat');
s.startTransaction(); d.probe.insertOne({_id:'transaction',value:1});
d.probe.updateOne({_id:'transaction'},{$inc:{value:1}});
if(d.probe.findOne({_id:'transaction'}).value!==2)quit(3);
s.commitTransaction(); s.endSession();
if(db.getSiblingDB('repaircalc_compat').probe.findOne({_id:'transaction'}).value!==2)quit(4);
'''
CRUD = "const d=db.getSiblingDB('repaircalc_compat');if(d.runCommand({ping:1}).ok!==1)quit(2);d.probe.updateOne({_id:'tick'},{$inc:{value:1}},{upsert:true});if(!d.probe.findOne({_id:'tick'}))quit(3);"


def check(image, *, remote=False, seconds=95, startup_timeout=60, memory="1g", cpus="1.0"):
    if not re.fullmatch(r"(?:docker\.io/library/)?mongo:[A-Za-z0-9_.-]+(?:@sha256:[0-9a-f]{64})?", image):
        raise ValueError("Expected an official Mongo image reference")
    if not 91 <= seconds <= 600 or not 10 <= startup_timeout <= 300:
        raise ValueError("Compatibility duration must be 91..600s and startup timeout 10..300s")
    report = {"status": "error", "probe_performed": False, **diagnostics(image)}
    if not isinstance(report["daemon"], dict) or not report["daemon"].get("kernel"):
        report["error_code"] = "docker_unavailable"
        return report
    kernel = re.match(r"^(\d+)\.(\d+)", report["daemon"]["kernel"])
    # Older kernels are outside the rseq regression. Other kernels are assessed by
    # actually running the selected binary, including its Ubuntu signature handling.
    if remote and kernel and tuple(map(int, kernel.groups())) < (6, 19):
        report.update(status="ok", reason="daemon_kernel_before_rseq_regression")
        return report
    token = uuid.uuid4().hex
    name = "repaircalc_mongo_compat_" + token
    report["probe_performed"] = True
    report["probe_container"] = name
    started = False
    began = time.monotonic()

    def state():
        return json_command(["docker", "inspect", "--format", STATE_FORMAT, name]) or {}

    def healthy():
        info = state()
        report["container_state"] = {key: info.get("state", {}).get(key) for key in ("Status", "ExitCode", "OOMKilled")}
        report["restarts"] = info.get("restarts", 0)
        return info.get("state", {}).get("Status") == "running" and info.get("restarts", 0) == 0

    def execute(script):
        return run(["docker", "exec", name, "mongosh", "--quiet", "--eval", script], timeout=15).returncode == 0

    def wait_for(script):
        deadline = time.monotonic() + startup_timeout
        while True:
            if not healthy():
                raise ProbeFailure("mongodb_process_exited")
            if execute(script):
                return
            if time.monotonic() >= deadline:
                raise ProbeFailure("mongodb_startup_timeout")
            time.sleep(2)

    try:
        if report["image_details"] is None:
            if run(["docker", "pull", image], timeout=300).returncode:
                raise ProbeFailure("mongodb_image_unavailable")
            report["image_details"] = diagnostics(image)["image_details"]
        result = run(["docker", "run", "--detach", "--name", name,
                      "--label", "repaircalc.compatibility-probe=" + token, "--network", "none",
                      "--read-only", "--memory", str(memory), "--cpus", str(cpus),
                      "--tmpfs", "/data/db:rw,nosuid,size=512m", "--tmpfs", "/data/configdb:rw,nosuid,size=16m",
                      "--tmpfs", "/tmp:rw,nosuid,noexec,size=64m", image,
                      "mongod", "--replSet", "repaircalc_probe", "--bind_ip", "127.0.0.1", "--wiredTigerCacheSizeGB", "0.25"], timeout=40)
        started = True
        if result.returncode:
            raise ProbeFailure("mongodb_container_start_failed")
        wait_for("if(db.adminCommand({ping:1}).ok!==1)quit(2);")
        signature = run(["docker", "exec", name, "cat", "/proc/version_signature"])
        report["daemon"]["upstream_signature"] = signature.stdout.strip()[:500] if signature.returncode == 0 else None
        if not execute("rs.initiate({_id:'repaircalc_probe',members:[{_id:0,host:'127.0.0.1:27017'}]});"):
            raise ProbeFailure("mongodb_replica_set_init_failed")
        wait_for("if(!db.hello().isWritablePrimary)quit(2);")
        if not execute(TRANSACTION):
            raise ProbeFailure("mongodb_transaction_failed")
        report["transaction_verified"] = True
        deadline = time.monotonic() + seconds
        while True:
            if not healthy() or not execute(CRUD):
                raise ProbeFailure("mongodb_runtime_failure")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(5, remaining))
        report.update(status="ok", stable_seconds=seconds)
    except ProbeFailure as failure:
        report["error_code"] = failure.code
    finally:
        if started:
            owner = run(["docker", "inspect", "--format", '{{index .Config.Labels "repaircalc.compatibility-probe"}}', name])
            if owner.returncode == 0 and owner.stdout.strip() == token:
                if not healthy() and report["status"] == "ok":
                    report.update(status="error", error_code="mongodb_runtime_failure")
                if report["status"] != "ok":
                    logs = run(["docker", "logs", "--tail", "80", name])
                    report["container_logs"] = (logs.stdout + logs.stderr)[-32768:]
                    if "12257600" in report["container_logs"] or "mongodb cannot start" in report["container_logs"].lower():
                        report["error_code"] = classify_failure(report["container_logs"])
                removed = run(["docker", "rm", "--force", "--volumes", name])
                if removed.returncode:
                    report.update(status="error", error_code="mongodb_probe_cleanup_failed")
            elif report["status"] == "ok":
                report.update(status="error", error_code="mongodb_probe_cleanup_unverified")
        report["elapsed_seconds"] = round(time.monotonic() - began, 2)
    return report


def write_report(path, report):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, prefix=".mongo-report-", delete=False) as file:
        temporary = Path(file.name)
        try:
            os.chmod(temporary, 0o600)
            json.dump(report, file, ensure_ascii=True, indent=2)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    os.replace(temporary, path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("diagnose", "probe", "remote-check"))
    parser.add_argument("--image")
    parser.add_argument("--report", required=True)
    parser.add_argument("--seconds", type=int)
    parser.add_argument("--startup-timeout", type=int)
    parser.add_argument("--memory", default="1g")
    parser.add_argument("--cpus", default="1.0")
    args = parser.parse_args(argv)
    try:
        image = args.image or image_reference()
        seconds = args.seconds if args.seconds is not None else int(os.environ.get("MONGO_COMPAT_SECONDS", "95"))
        timeout = args.startup_timeout if args.startup_timeout is not None else int(os.environ.get("MONGO_COMPAT_START_TIMEOUT_SECONDS", "60"))
        report = diagnostics(image) if args.action == "diagnose" else check(image, remote=args.action == "remote-check", seconds=seconds, startup_timeout=timeout, memory=args.memory, cpus=args.cpus)
        if args.action == "diagnose":
            report["status"] = "ok" if report["daemon"] else "error"
    except (ValueError, OSError):
        report = {"status": "error", "error_code": "mongodb_compatibility_configuration_error"}
    write_report(args.report, report)
    print(json.dumps(report, ensure_ascii=True))
    return 0 if report["status"] == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(main())
