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
from urllib.parse import urlsplit, unquote


class PreflightError(Exception):
    pass


def command(argv, data=None, timeout=30):
    try:
        result = subprocess.run(argv, input=data, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise PreflightError("Deployment preflight command unavailable or timed out") from None
    if result.returncode:
        # Docker/database errors can include passwords; do not echo them.
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
            if service(containers, "repaircalc", "postgres") and not service(containers, "repaircalc", "mongo"):
                raise PreflightError("Legacy PostgreSQL cannot be adopted as MongoDB; use a fresh project and port. Existing data was not changed")
            for name in ("frontend", "backend", "mongo"):
                container = service(containers, "repaircalc", name)
                state = (container or {}).get("State", {})
                if state.get("Status") != "running" or (state.get("Health") or {}).get("Status") != "healthy":
                    raise PreflightError("Legacy RepairCalc stack is not healthy; automatic adoption refused")
            selected, adopted = "repaircalc", True
    # Never select an existing but different database after a port change on first migration.
    legacy = service(containers, "repaircalc", "frontend")
    if not pinned and environment == "prod" and selected != "repaircalc" and legacy:
        if service(containers, "repaircalc", "mongo"):
            raise PreflightError("Existing RepairCalc Mongo stack belongs to another project; retain its pinned project and volume")
    return selected, adopted, bool(occupied)


def database_volume(mongo, project):
    mounts = [m for m in mongo.get("Mounts", []) if m.get("Destination") == "/data/db"]
    if len(mounts) != 1 or mounts[0].get("Type") != "volume" or mounts[0].get("Name") != project + "_mongo-data":
        raise PreflightError("Unexpected MongoDB volume; refusing to replace or migrate existing data")
    return mounts[0]["Name"]


def compose(path, project):
    return ["docker", "compose", "--project-directory", str(path / "docker"), "--project-name", project,
            "--env-file", str(path / "env/.env"), "--env-file", str(path / "env/secret.env"),
            "-f", str(path / "docker/docker-compose.yml"), "-f", str(path / "docker/docker-compose.release.yml")]


def database_address(mongo):
    project = labels(mongo).get("com.docker.compose.project")
    network = mongo.get("NetworkSettings", {}).get("Networks", {}).get(str(project) + "_default") or {}
    for field in ("IPAddress", "GlobalIPv6Address"):
        try:
            address = ipaddress.ip_address(network.get(field, ""))
        except ValueError:
            continue
        if not address.is_loopback and not address.is_unspecified and not address.is_link_local:
            return str(address)
    raise PreflightError("Existing MongoDB has no verified Docker network address; refusing a localhost authentication check")


MONGO_QUERY = r'''
const p = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const host = p.host.includes(':') ? '['+p.host+']' : p.host;
const database = p.settings.MONGO_DATABASE;
const uri = 'mongodb://'+encodeURIComponent(p.settings.MONGO_USER)+':'+encodeURIComponent(p.settings.MONGO_PASSWORD)+'@'+host+':'+p.settings.MONGO_PORT+'/'+encodeURIComponent(database)+'?directConnection=true&serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000&replicaSet='+encodeURIComponent(p.settings.MONGO_REPLICA_SET);
const c = new Mongo(uri); const d = c.getDB(database);
if (d.runCommand({ping:1}).ok !== 1) quit(2);
if (p.operation === 'auth') { print(JSON.stringify({ok:1})); }
else {
 const h = c.getDB('admin').runCommand({hello:1});
 const v = c.getDB('admin').runCommand({buildInfo:1}).version;
 const pair = d.publication_pointer.findOne({_id:'active'});
 const initialized = !!(pair && d.catalog_release.findOne({_id:pair.catalogReleaseId}) && d.rule_set.findOne({_id:pair.ruleSetId}) && d.product_revision.countDocuments({releaseId:pair.catalogReleaseId}) > 0);
 print(JSON.stringify({version:v, replicaSet:h.setName, primary:h.isWritablePrimary, initialized:initialized, invalidPointer:!!pair && !initialized}));
}
'''


def query(mongo, settings, operation):
    payload = {"host": database_address(mongo), "settings": settings, "operation": operation}
    try:
        return json.loads(command(["docker", "exec", "-i", mongo["Id"], "mongosh", "--nodb", "--quiet", "--eval", MONGO_QUERY], json.dumps(payload)))
    except (PreflightError, ValueError):
        raise PreflightError("Cannot query existing MongoDB over the Docker network with deployment credentials; verify Mongo credentials. No services were restarted") from None


def runtime_check(path, config):
    mongo = config["services"]["mongo"]
    settings = mongo["environment"]
    argv = [sys.executable, str(path / "scripts/mongo_runtime.py"), "remote-check",
            "--image", mongo["image"], "--report", str(path / "mongo-compatibility.json"),
            "--seconds", str(settings.get("MONGO_COMPAT_SECONDS", "95")),
            "--startup-timeout", str(settings.get("MONGO_COMPAT_START_TIMEOUT_SECONDS", "60")),
            "--memory", str(mongo.get("mem_limit", "1g")), "--cpus", str(mongo.get("cpus", "1.0"))]
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=750, check=False)
        report = json.loads(result.stdout)
        if not isinstance(report, dict):
            raise ValueError("Invalid compatibility report")
        if result.returncode == 0 and report.get("status") == "ok":
            return report
        code = report.get("error_code", "mongodb_compatibility_failed")
        if not isinstance(code, str) or not re.fullmatch(r"(?:mongodb|docker)_[a-z_]+", code):
            code = "mongodb_compatibility_failed"
    except (OSError, ValueError, subprocess.TimeoutExpired):
        code = "mongodb_compatibility_failed"
    raise PreflightError("Mongo compatibility failed (" + code + "); running services were not restarted")


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
    desired = config["services"]["backend"]["environment"]
    mongo_env = config["services"]["mongo"]["environment"]
    database = unquote(urlsplit(desired["MONGO_URI"]).path.lstrip('/'))
    settings = {"MONGO_DATABASE": database, "MONGO_USER": desired["MONGO_USER"], "MONGO_PASSWORD": desired["MONGO_PASSWORD"],
                "MONGO_PORT": mongo_env["MONGO_PORT"], "MONGO_REPLICA_SET": mongo_env["MONGO_REPLICA_SET"]}
    mongo = service(containers, selected, "mongo")
    if not mongo and service(containers, selected, "postgres"):
        raise PreflightError("Existing PostgreSQL project requires a separate MongoDB deployment; "
                             "migrate and verify PostgreSQL data explicitly, use a free "
                             "DEPLOY_FRONTEND_PORT and switch ingress only after verification. "
                             "No automatic data migration is allowed")
    volume = selected + "_mongo-data"
    preserve = False
    if mongo:
        volume = database_volume(mongo, selected)
        if mongo.get("State", {}).get("Status") != "running":
            raise PreflightError("Existing MongoDB is not running; refusing automatic reinitialization")
        backend = service(containers, selected, "backend")
        previous = env(backend or {})
        if previous.get("MONGO_USER") != settings["MONGO_USER"] or unquote(urlsplit(previous.get("MONGO_URI", "")).path.lstrip('/')) != database:
            raise PreflightError("Mongo database/user differ from the existing backend; keep current settings")
        root = {**settings, "MONGO_DATABASE": "admin", "MONGO_USER": mongo_env["MONGO_INITDB_ROOT_USERNAME"], "MONGO_PASSWORD": mongo_env["MONGO_INITDB_ROOT_PASSWORD"]}
        query(mongo, root, "auth")
        state = query(mongo, settings, "state")
        image_tag = config["services"]["mongo"]["image"].split('@', 1)[0].rsplit(":", 1)[-1]
        major = re.match(r"(\d+)(?:[.\-]|$)", image_tag)
        if not major or state["version"].split('.')[0] != major[1]:
            raise PreflightError("MongoDB major upgrade requires a separate migration; existing database was not changed")
        if state.get("replicaSet") != settings["MONGO_REPLICA_SET"] or not state.get("primary") or state.get("invalidPointer"):
            raise PreflightError("Existing Mongo replica set or active publication is inconsistent; deployment refused")
        preserve = state["initialized"]
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
    compatibility = runtime_check(args.path, config)
    return {"project": selected, "volume": volume, "adopted_legacy": adopted,
            "existing_database": bool(mongo), "preserve_catalog": preserve, "database": database,
            "mongo_id": mongo["Id"] if mongo else None, "mongo_runtime": {
                "status": compatibility["status"], "daemon": compatibility.get("daemon"),
                "probe_performed": compatibility.get("probe_performed", False)}}


def backup(args):
    saved = json.loads((args.path / "deployment-target.json").read_text())
    mongo = json.loads(command(["docker", "inspect", saved["mongo_id"]]))[0]
    if database_volume(mongo, saved["project"]) != saved["volume"]:
        raise PreflightError("Database changed before backup; deployment refused")
    target = args.path / "database-before.archive.gz"
    if target.exists() or target.with_name(target.name + '.json').exists():
        raise PreflightError("Backup already exists; refusing to overwrite it")
    argv = [sys.executable, str(args.path / "scripts/mongo-backup.py"), "backup", "--file", str(target),
            "--project-name", saved["project"], "--database", saved["database"]]
    for file in (".env", "secret.env"):
        argv += ["--env-file", str(args.path / "env" / file)]
    for file in ("docker-compose.yml", "docker-compose.release.yml"):
        argv += ["--compose-file", str(args.path / "docker" / file)]
    command(argv, timeout=600)
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
