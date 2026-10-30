#!/usr/bin/env python3
"""Collect bounded private deployment logs and print ONLY a safe status summary."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

SERVICES = ("mongo", "mongo-init", "init-data", "backend", "llm", "ollama", "frontend")
LIMIT = 65536


def run(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, errors="replace", timeout=20, check=False)
        return {"rc": result.returncode, "stdout": result.stdout[-LIMIT:], "stderr": result.stderr[-LIMIT:]}
    except (OSError, subprocess.TimeoutExpired):
        return {"rc": -1, "stdout": "", "stderr": "Diagnostic command unavailable or timed out"}


def rows(text):
    try:
        data = json.loads(text)
        return data if isinstance(data, list) else [data]
    except ValueError:
        # Compose versions differ: JSON array vs newline-delimited JSON objects.
        result = []
        for line in text.splitlines():
            try:
                item = json.loads(line)
                if isinstance(item, dict):
                    result.append(item)
            except ValueError:
                pass
        return result


def collect(path, project):
    compose = ["docker", "compose", "--project-directory", str(path / "docker"), "--project-name", project,
               "--env-file", str(path / "env/.env"), "--env-file", str(path / "env/secret.env"),
               "-f", str(path / "docker/docker-compose.yml"), "-f", str(path / "docker/docker-compose.release.yml")]
    try:
        failure = json.loads((path / "deployment-failure.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        failure = {"rc": -1}
    if not isinstance(failure, dict):
        failure = {"rc": -1}
    ps = run(compose + ["ps", "--all", "--format", "json"])
    logs = {service: run(compose + ["logs", "--no-color", "--tail", "80", service]) for service in SERVICES}
    containers = []
    private_states = []
    for item in rows(ps["stdout"]):
        if not isinstance(item, dict) or item.get("Service") not in SERVICES:
            continue
        service = item["Service"]
        # Inspect only State, not Config/Env (which would contain credentials).
        identifier = item.get("ID", "")
        state = {}
        if isinstance(identifier, str) and 12 <= len(identifier) <= 64 and all(c in "0123456789abcdef" for c in identifier):
            inspected = run(["docker", "inspect", "--format", "{{json .State}}", identifier])
            private_states.append({"service": service, "result": inspected})
            try:
                state = json.loads(inspected["stdout"])
                if not isinstance(state, dict):
                    state = {}
            except ValueError:
                pass
        status = state.get("Status", item.get("State"))
        if status not in ("created", "running", "paused", "restarting", "removing", "exited", "dead"):
            status = "unknown"
        health_state = state.get("Health")
        health = health_state.get("Status") if isinstance(health_state, dict) else item.get("Health")
        if health not in ("healthy", "unhealthy", "starting"):
            health = "unknown"
        exit_code = state.get("ExitCode", item.get("ExitCode"))
        containers.append({"service": service, "state": status, "health": health,
                           "exit_code": exit_code if type(exit_code) is int else None,
                           "oom_killed": state.get("OOMKilled") is True})
    # Raw messages can contain arbitrary application secrets. Classify, never print them.
    messages = json.dumps({"failure": failure, "logs": logs, "states": private_states}).lower()
    signals = []
    for signal, phrases in {
        "port_conflict": ("address already in use", "port is already allocated"),
        "invalid_bind_address": ("cannot assign requested address",),
        "insufficient_disk_space": ("no space left on device",),
        "database_authentication_failed": ("authentication failed", "authenticationfailed"),
        "database_configuration_error": ("replicasetnoprimary", "notyetinitialized", "replica set configuration"),
        "unhealthy_container": ("unhealthy",),
        "wait_timeout": ("timed out", "timeout exceeded"),
    }.items():
        if any(phrase in messages for phrase in phrases):
            signals.append(signal)
    if any(item["oom_killed"] for item in containers):
        signals.append("oom_killed")
    if any(item["service"] == "init-data" and item["exit_code"] not in (None, 0) for item in containers):
        signals.append("bootstrap_failed")
    report_path = path / "deployment-diagnostics.json"
    report = {"failure": failure, "ps": ps, "logs": logs, "states": private_states}
    # Private file only: never upload this automatically to Actions artifacts/logs.
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path, prefix=".diagnostics-", delete=False) as file:
        temporary = Path(file.name)
        try:
            os.chmod(temporary, 0o600)
            json.dump(report, file, ensure_ascii=False)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    os.replace(temporary, report_path)
    rc = failure.get("rc")
    return {"failure_rc": rc if type(rc) is int else None, "signals": signals,
            "containers": containers, "private_report": str(report_path)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-path", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(collect(Path(args.release_path), args.project), ensure_ascii=True))
        return 0
    except (OSError, ValueError, TypeError):
        print("Deployment diagnostics could not be collected", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
