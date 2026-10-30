#!/usr/bin/env python3
"""Resolve an existing RepairCalc deployment safely; never stop containers or copy databases."""

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys


class PreflightError(Exception):
    pass


def command(argv, data=None, timeout=30):
    try:
        result = subprocess.run(argv, input=data, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise PreflightError("Deployment preflight command unavailable or timed out") from None
    if result.returncode:
        # Docker/psql errors can include passwords; do not echo them.
        raise PreflightError("Deployment preflight command failed; check Docker and database access on the stand")
    return result.stdout


def labels(container):
    return container.get("Config", {}).get("Labels") or {}


def service(containers, project, name):
    found = [c for c in containers if labels(c).get("com.docker.compose.project") == project
             and labels(c).get("com.docker.compose.service") == name]
    if len(found) > 1:
        raise PreflightError("Ambiguous Compose service; refusing to choose a database")
    return found[0] if found else None


def env(container):
    return dict(item.split("=", 1) for item in container.get("Config", {}).get("Env", []) if "=" in item)


def overlaps(left, right):
    left, right = ipaddress.ip_address(left or "0.0.0.0"), ipaddress.ip_address(right or "0.0.0.0")
    return left == right or left.is_unspecified or right.is_unspecified


def owners(containers, host, port):
    result = []
    for container in containers:
        if container.get("State", {}).get("Status") not in ("running", "restarting", "paused"):
            continue
        for bindings in (container.get("HostConfig", {}).get("PortBindings") or {}).values():
            if any(str(p.get("HostPort")) == str(port) and overlaps(p.get("HostIp"), host) for p in bindings or []):
                result.append(container)
                break
    return result


def choose(containers, project, environment, host, port, pinned=None, managed=False):
    occupied = owners(containers, host, port)
    if len(occupied) > 1:
        raise PreflightError("Frontend port has multiple owners; refusing automatic takeover")
    selected = pinned["project"] if pinned else project
    adopted = False
    if occupied:
        owner = occupied[0]
        owner_project = labels(owner).get("com.docker.compose.project")
        if labels(owner).get("com.docker.compose.service") != "frontend":
            raise PreflightError("Frontend port belongs to another service; nothing was stopped")
        if owner_project != selected:
            if pinned or managed or environment != "prod" or project != "repaircalc_prod" or owner_project != "repaircalc":
                raise PreflightError("Frontend port belongs to another Compose project; nothing was stopped")
            for name in ("frontend", "backend", "postgres"):
                container = service(containers, "repaircalc", name)
                state = (container or {}).get("State", {})
                if state.get("Status") != "running" or (state.get("Health") or {}).get("Status") != "healthy":
                    raise PreflightError("Legacy RepairCalc stack is not healthy; automatic adoption refused")
            selected, adopted = "repaircalc", True
    # Never select an existing but different database after a port change on first migration.
    legacy = service(containers, "repaircalc", "frontend")
    if not pinned and not managed and environment == "prod" and selected != "repaircalc" and legacy:
        if legacy.get("State", {}).get("Status") == "running":
            raise PreflightError("Legacy RepairCalc is running on another port; use its current port to preserve its database")
    return selected, adopted, bool(occupied)


def database_volume(postgres, project):
    mounts = [m for m in postgres.get("Mounts", []) if m.get("Destination") == "/var/lib/postgresql/data"]
    if len(mounts) != 1 or mounts[0].get("Type") != "volume" or mounts[0].get("Name") != project + "_pgdata":
        raise PreflightError("Unexpected PostgreSQL volume; refusing to replace or migrate existing data")
    return mounts[0]["Name"]


def compose(path, project):
    return ["docker", "compose", "--project-directory", str(path / "docker"), "--project-name", project,
            "--env-file", str(path / "env/.env"), "--env-file", str(path / "env/secret.env"),
            "-f", str(path / "docker/docker-compose.yml"), "-f", str(path / "docker/docker-compose.release.yml")]


def database_address(postgres):
    project = labels(postgres).get("com.docker.compose.project")
    network = postgres.get("NetworkSettings", {}).get("Networks", {}).get(str(project) + "_default") or {}
    for field in ("IPAddress", "GlobalIPv6Address"):
        try:
            address = ipaddress.ip_address(network.get(field, ""))
        except ValueError:
            continue
        if not address.is_loopback and not address.is_unspecified and not address.is_link_local:
            return str(address)
    raise PreflightError("Existing PostgreSQL has no verified Docker network address; refusing a localhost authentication check")


def query(postgres, settings, sql):
    # PostgreSQL images commonly trust BOTH Unix sockets and localhost TCP. Use the
    # container's bridge address so the same network auth rules as the backend apply.
    # Password goes through stdin, never argv or diagnostic output.
    address = database_address(postgres)
    shell = ('IFS= read -r PGPASSWORD; export PGPASSWORD; export PGCONNECT_TIMEOUT=5; '
             'export PGOPTIONS="-c default_transaction_read_only=on"; '
             'exec psql -X -A -t -v ON_ERROR_STOP=1 -h "$1" -U "$2" -d "$3" -c "$4"')
    try:
        return command(["docker", "exec", "-i", postgres["Id"], "sh", "-c", shell, "sh",
                        address, settings["POSTGRES_USER"], settings["POSTGRES_DB"], sql], settings["POSTGRES_PASSWORD"] + "\n").strip()
    except PreflightError:
        raise PreflightError("Cannot query existing PostgreSQL over the Docker network with deployment credentials; verify DB_PASSWORD, DB_USER and DB_NAME. No services were restarted") from None


def plan(args):
    ids = command(["docker", "ps", "-aq"]).split()
    containers = json.loads(command(["docker", "inspect"] + ids)) if ids else []
    marker = args.base / "compose-project.json"
    pinned = json.loads(marker.read_text()) if marker.exists() else None
    if pinned and (pinned.get("project") not in (args.project, "repaircalc")
                   or (pinned["project"] == "repaircalc" and args.environment != "prod")):
        raise PreflightError("Stored Compose project does not match this environment")
    selected, adopted, occupied = choose(containers, args.project, args.environment, args.host, args.port,
                                        pinned, (args.base / "current").exists())
    if not occupied:
        family = socket.AF_INET6 if ":" in args.host else socket.AF_INET
        try:
            with socket.socket(family, socket.SOCK_STREAM) as sock:
                sock.bind((args.host, args.port))
        except OSError:
            raise PreflightError("Frontend address/port is unavailable; nothing was stopped") from None
    config = json.loads(command(compose(args.path, selected) + ["config", "--format", "json"]))
    settings = config["services"]["postgres"]["environment"]
    postgres = service(containers, selected, "postgres")
    volume = selected + "_pgdata"
    preserve = False
    if postgres:
        volume = database_volume(postgres, selected)
        if postgres.get("State", {}).get("Status") != "running":
            raise PreflightError("Existing PostgreSQL is not running; refusing automatic reinitialization")
        previous = env(postgres)
        if any(previous.get(key) != settings[key] for key in ("POSTGRES_USER", "POSTGRES_DB")):
            raise PreflightError("DB_USER/DB_NAME differ from the existing database; keep current settings")
        version = query(postgres, settings, "SHOW server_version_num")
        image_tag = config["services"]["postgres"]["image"].rsplit(":", 1)[-1]
        major = re.match(r"(\d+)(?:[.\-]|$)", image_tag)
        if not major or int(version) // 10000 != int(major[1]):
            raise PreflightError("PostgreSQL major upgrade requires a separate migration; existing database was not changed")
        exists = query(postgres, settings, "SELECT to_regclass('repaircalc.catalog_release') IS NOT NULL") == "t"
        preserve = exists and query(postgres, settings, "SELECT EXISTS (SELECT 1 FROM repaircalc.catalog_release)") == "t"
        backend = service(containers, selected, "backend")
        old_admin = env(backend or {})
        new_admin = config["services"]["backend"]["environment"]
        if old_admin.get("ADMIN_USERNAME") and old_admin.get("ADMIN_PASSWORD_HASH"):
            if not new_admin.get("ADMIN_USERNAME") or not new_admin.get("ADMIN_PASSWORD_HASH"):
                raise PreflightError("Existing admin is enabled; set ADMIN_USERNAME and ADMIN_PASSWORD_HASH before deployment")
    else:
        # A volume without its original container may contain real data, not a fresh DB.
        volumes = command(["docker", "volume", "ls", "--format", "{{.Name}}"]).splitlines()
        if volume in volumes or pinned:
            raise PreflightError("Existing database container is missing; refusing to start against an unverified volume")
    if pinned and pinned.get("volume") != volume:
        raise PreflightError("Database volume changed since the previous deployment; automatic switch refused")
    return {"project": selected, "volume": volume, "adopted_legacy": adopted,
            "existing_database": bool(postgres), "preserve_catalog": preserve,
            "postgres_id": postgres["Id"] if postgres else None}


def backup(args):
    saved = json.loads((args.path / "deployment-target.json").read_text())
    postgres = json.loads(command(["docker", "inspect", saved["postgres_id"]]))[0]
    if database_volume(postgres, saved["project"]) != saved["volume"]:
        raise PreflightError("Database changed before backup; deployment refused")
    target = args.path / "database-before.dump"
    partial = args.path / "database-before.dump.partial"
    try:
        with partial.open("xb") as stream:
            os.chmod(partial, 0o600)
            result = subprocess.run(["docker", "exec", postgres["Id"], "sh", "-c",
                                     'exec pg_dump --format=custom -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                                    stdout=stream, stderr=subprocess.PIPE, timeout=300, check=False)
        if result.returncode or not partial.stat().st_size:
            raise PreflightError("Database backup failed; running services were not changed")
        with partial.open("rb") as stream:
            checked = subprocess.run(["docker", "exec", "-i", postgres["Id"], "pg_restore", "--list"],
                                     stdin=stream, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30, check=False)
        if checked.returncode:
            raise PreflightError("Database backup archive check failed; running services were not changed")
        os.replace(partial, target)
    finally:
        partial.unlink(missing_ok=True)
    return {"backup": str(target)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "backup"))
    parser.add_argument("--path", type=Path, required=True)
    parser.add_argument("--base", type=Path)
    parser.add_argument("--project")
    parser.add_argument("--environment")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(plan(args) if args.action == "plan" else backup(args)))
        return 0
    except PreflightError as error:
        print(json.dumps({"error": str(error)}))
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        print(json.dumps({"error": "Deployment preflight/backup failed; inspect the stand privately"}))
    return 1


if __name__ == "__main__":
    sys.exit(main())
