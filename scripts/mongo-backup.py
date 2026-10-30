#!/usr/bin/env python3
"""Consistent Mongo archive and restore into a new database; credentials stay inside Mongo.

Backup briefly stops the Java writer and starts it again in finally. Restore refuses an
existing target database and verifies document counts and archive SHA-256.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
TOOLS = r'''
set -eu
operation="$1"
export REPAIRCALC_BACKUP_DB="$2" REPAIRCALC_RESTORE_DB="${3:-}"
config=$(mktemp /tmp/repaircalc-tools.XXXXXX)
export REPAIRCALC_TOOLS_CONFIG="$config"
trap 'rm -f "$config"' EXIT
chmod 600 "$config"
mongosh --nodb --quiet --eval 'require("fs").writeFileSync(process.env.REPAIRCALC_TOOLS_CONFIG, JSON.stringify({password:process.env.MONGO_INITDB_ROOT_PASSWORD}))' </dev/null
case "$operation" in
  backup)
    mongodump --host 127.0.0.1 --port "$MONGO_PORT" --username "$MONGO_INITDB_ROOT_USERNAME" --authenticationDatabase admin --config "$config" --db "$REPAIRCALC_BACKUP_DB" --archive --gzip
    ;;
  restore)
    mongosh --port "$MONGO_PORT" --quiet --eval 'const a=db.getSiblingDB("admin");if(!a.auth(process.env.MONGO_INITDB_ROOT_USERNAME,process.env.MONGO_INITDB_ROOT_PASSWORD))quit(2);if(db.getSiblingDB(process.env.REPAIRCALC_RESTORE_DB).getCollectionNames().length)quit(3);' </dev/null
    mongorestore --host 127.0.0.1 --port "$MONGO_PORT" --username "$MONGO_INITDB_ROOT_USERNAME" --authenticationDatabase admin --config "$config" --archive --gzip --stopOnError --nsInclude "$REPAIRCALC_BACKUP_DB.*" --nsFrom "$REPAIRCALC_BACKUP_DB.*" --nsTo "$REPAIRCALC_RESTORE_DB.*"
    ;;
  stats)
    mongosh --port "$MONGO_PORT" --quiet --eval 'const a=db.getSiblingDB("admin");if(!a.auth(process.env.MONGO_INITDB_ROOT_USERNAME,process.env.MONGO_INITDB_ROOT_PASSWORD))quit(2);const t=db.getSiblingDB(process.env.REPAIRCALC_BACKUP_DB);const counts={};for(const n of t.getCollectionNames().sort())if(!n.startsWith("system."))counts[n]=t.getCollection(n).countDocuments({});print(JSON.stringify(counts));' </dev/null
    ;;
  *) exit 2 ;;
esac
'''


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "restore"))
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--database", default="repaircalc")
    parser.add_argument("--target-database")
    parser.add_argument("--project-name", default="repaircalc")
    parser.add_argument("--env-file", action="append")
    parser.add_argument("--compose-file", action="append")
    args = parser.parse_args()
    for database in (args.database, args.target_database):
        if database is not None and not re.fullmatch(r"[A-Za-z0-9_-]{1,63}", database):
            raise ValueError("Use a database name with 1–63 letters, digits, hyphens or underscores")
    if args.action == "restore" and (not args.target_database or args.target_database == args.database):
        raise ValueError("Restore requires a distinct --target-database")
    compose = ["docker", "compose", "--project-name", args.project_name]
    for file in args.env_file or ["env/.env", "env/secret.env"]:
        compose += ["--env-file", str(Path(file).resolve())]
    for file in args.compose_file or ["docker/docker-compose.yml"]:
        compose += ["-f", str(Path(file).resolve())]

    def run(arguments, **kwargs):
        result = subprocess.run(compose + arguments, cwd=ROOT, stderr=subprocess.PIPE, **kwargs)
        if result.returncode:
            raise RuntimeError("Mongo operation failed; no existing database was replaced")
        return result

    def tools(operation, database, target="", **kwargs):
        return run(["exec", "-T", "mongo", "sh", "-c", TOOLS, "repaircalc-tools", operation, database, target], **kwargs)

    def stats(database):
        return json.loads(tools("stats", database, stdout=subprocess.PIPE).stdout)

    archive = args.file.resolve()
    metadata = archive.with_name(archive.name + ".json")
    if args.action == "backup":
        if archive.exists() or metadata.exists():
            raise ValueError("Backup destination already exists")
        archive.parent.mkdir(parents=True, exist_ok=True)
        services = run(["ps", "--status", "running", "--services"], stdout=subprocess.PIPE, text=True).stdout.splitlines()
        running = "backend" in services
        partial = archive.with_name(archive.name + ".partial-" + uuid.uuid4().hex)
        try:
            if running:
                print("Stopping Java briefly for a consistent Mongo backup", flush=True)
                run(["stop", "backend"], stdout=subprocess.DEVNULL)
            counts = stats(args.database)
            with partial.open("xb") as output:
                partial.chmod(0o600)
                tools("backup", args.database, stdout=output)
            record = {"database": args.database, "sha256": digest(partial), "collections": counts}
            partial.rename(archive)
            with metadata.open("x", encoding="utf-8") as output:
                metadata.chmod(0o600)
                json.dump(record, output, indent=2)
        finally:
            if running:
                run(["start", "backend"], stdout=subprocess.DEVNULL)
    else:
        record = json.loads(metadata.read_text(encoding="utf-8"))
        if record["database"] != args.database or digest(archive) != record["sha256"]:
            raise ValueError("Archive database or SHA-256 does not match its metadata")
        with archive.open("rb") as source:
            tools("restore", args.database, args.target_database, stdin=source, stdout=subprocess.DEVNULL)
        if stats(args.target_database) != record["collections"]:
            raise RuntimeError("Restored collection counts do not match backup")
    print("Mongo " + args.action + " verified", flush=True)


if __name__ == "__main__":
    main()
