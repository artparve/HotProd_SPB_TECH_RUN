#!/usr/bin/env python3
"""Explicit PostgreSQL snapshot -> empty MongoDB migration. Never switches ingress."""

import argparse
from copy import deepcopy
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

TABLES = (
    "admin_audit_event", "admin_classification_job", "admin_label_review",
    "admin_product", "admin_product_version", "admin_publication", "catalog_release",
    "product", "product_revision", "product_work", "rule_set", "source_record", "work_type",
)


def run(argv, data=None):
    result = subprocess.run(argv, input=data, text=True, capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Migration command failed; inspect the database privately (raw errors suppressed)")
    return result.stdout


def private_write(path, content):
    # Refuse overwrites, including symlinks. Database snapshots are private artifacts.
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(content)


def export_snapshot(container, output):
    sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
    sql += "SELECT json_build_object('tables', array_agg(table_name ORDER BY table_name))::jsonb::text FROM information_schema.tables WHERE table_schema='repaircalc' AND table_type='BASE TABLE';\n"
    for table in TABLES:
        sql += ("SELECT json_build_object('table', '" + table + "', 'rows', "
                "coalesce(json_agg(t), '[]'::json))::jsonb::text FROM repaircalc." + table + " t;\n")
    sql += "COMMIT;\n"
    data = run(["docker", "exec", "-i", container, "sh", "-c",
                'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], sql)
    snapshot = load_snapshot(data)
    private_write(output, data)
    return {"sha256": hashlib.sha256(data.encode()).hexdigest(),
            "tables": {name: len(rows) for name, rows in snapshot.items()}}


def load_snapshot(data):
    lines = [json.loads(line, parse_float=Decimal) for line in data.splitlines() if line.strip()]
    if not lines or lines[0].get("tables") != sorted(TABLES):
        raise ValueError("Unsupported PostgreSQL tables; migration must explicitly handle every source table")
    tables = {}
    for item in lines[1:]:
        name = item["table"]
        if name not in TABLES or name in tables or not isinstance(item["rows"], list):
            raise ValueError("Invalid or duplicate snapshot table")
        tables[name] = item["rows"]
    if set(tables) != set(TABLES):
        raise ValueError("Incomplete PostgreSQL snapshot")
    return tables


def extended(value):
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("Non-finite database number")
        return {"$numberDecimal": str(value)}
    if isinstance(value, dict):
        return {key: extended(item) for key, item in value.items()}
    if isinstance(value, list):
        return [extended(item) for item in value]
    return value


def date(value):
    if value is None:
        return None
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.utcoffset() is None:
        raise ValueError("Timestamp must include timezone")
    return {"$date": instant.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")}


def plain_json(value):
    """Jackson-compatible compact JSON without converting exact decimals to floats."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("Non-finite database number")
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k, ensure_ascii=False) + ":" + plain_json(v) for k, v in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(map(plain_json, value)) + "]"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def transform(tables, reference_rules=None, reference_catalog=None):
    job_counts = Counter(row["version_id"] for row in tables["admin_classification_job"])
    if any(count > 1 for count in job_counts.values()):
        raise ValueError("Multiple historical jobs per version require an explicit history migration; none were discarded")
    if any(row["status"] not in ("failed", "succeeded") for row in tables["admin_classification_job"]):
        raise ValueError("Drain or explicitly cancel pending classification jobs before migration")
    out = defaultdict(list)
    by_id = {table: {row["id"]: row for row in rows if "id" in row} for table, rows in tables.items()}
    for name, rows in tables.items():
        for ordinal, row in enumerate(rows):
            # Preserve every original field, including SQL-only metadata and work evidence.
            out["legacy_postgres"].append({"_id": f"{name}:{ordinal}", "table": name, "row": extended(row)})

    def add(collection, row):
        out[collection].append(extended(row))

    def require(table, key):
        if key not in by_id[table]:
            raise ValueError("Broken foreign key into " + table)
        return by_id[table][key]

    works = defaultdict(list)
    for row in tables["product_work"]:
        works[(row["release_id"], row["product_id"])].append(row["work_code"])
    for row in tables["catalog_release"]:
        add("catalog_release", {"_id": row["id"], **{key: row[key] for key in ("version", "fingerprint", "manifest")}})
    for row in tables["product_revision"]:
        require("catalog_release", row["release_id"])
        require("product", row["product_id"])
        keys = {"name": "name", "category": "category", "kind": "kind", "price": "price",
                "package_size": "packageSize", "unit": "unit", "sale_unit": "saleUnit", "wet": "wet",
                "high_load": "highLoad", "unheated": "unheated", "description": "description", "source": "source",
                "consumption": "consumption", "roll_width": "rollWidth", "roll_length": "rollLength", "rapport": "rapport"}
        doc = {dest: row[key] for key, dest in keys.items()}
        doc.update(_id=row["id"], id=row["product_id"], releaseId=row["release_id"],
                   works=sorted(works[(row["release_id"], row["product_id"])]))
        doc["price"] = Decimal(str(row["price"]))
        if row["kind"] == "covering":
            reference = Path(reference_catalog) if reference_catalog is not None else Path(__file__).resolve().parents[1] / "data/catalog-v1.json"
            products = json.loads(reference.read_text(), parse_float=Decimal)["products"]
            prepared = next((p for p in products if p["id"] == row["product_id"]), None)
            identity = (("name", "name"), ("category", "category"), ("unit", "unit"),
                        ("sale_unit", "saleUnit"), ("package_size", "packageSize"))
            if (not prepared or prepared.get("kind") != "covering"
                    or prepared.get("materialFamily") not in ("tile", "laminate", "vinyl")
                    or any(row[old] != prepared.get(new) for old, new in identity)):
                raise ValueError("Covering family requires an explicitly reviewed reference matching UUID and product facts")
            doc["materialFamily"] = prepared["materialFamily"]
        add("product_revision", doc)
    identities = {}
    for row in tables["source_record"]:
        require("catalog_release", row["release_id"])
        require("product", row["product_id"])
        identity = (row["source_namespace"], row["external_id"])
        if identity in identities and identities[identity] != row["product_id"]:
            raise ValueError("Conflicting source identity")
        identities[identity] = row["product_id"]
        add("source_record", {"_id": row["id"], "releaseId": row["release_id"], "productId": row["product_id"],
                              "namespace": identity[0], "externalId": identity[1], "raw": row["raw"]})
    for row in tables["rule_set"]:
        require("catalog_release", row["catalog_release_id"])
        bundle = deepcopy(row["bundle"])
        # The new Java record has a primitive priority; Jackson 3 rejects a
        # missing creator property. Zero retains legacy assignment semantics.
        for assignment in bundle["assignments"]:
            assignment.setdefault("priority", 0)
        if not bundle.get("kits"):
            reference = Path(reference_rules) if reference_rules is not None else Path(__file__).resolve().parents[1] / "data/rules-v1.json"
            defaults = json.loads(reference.read_text())
            if len(defaults.get("kits", [])) != 8:
                raise ValueError("Reference release must declare all eight Java material dependencies")
            bundle["kits"] = deepcopy(defaults["kits"])
        add("rule_set", {"_id": row["id"], "catalogReleaseId": row["catalog_release_id"],
                         **{key: row[key] for key in ("version", "fingerprint")}, "bundle": bundle})
    for row in tables["admin_product"]:
        version = require("admin_product_version", row["current_version_id"])
        if version["product_id"] != row["id"]:
            raise ValueError("Admin current version belongs to a different product")
        identity = (row["source_namespace"], row["external_id"])
        if identity in identities and identities[identity] != row["id"]:
            raise ValueError("Admin source identity mismatch")
        identities[identity] = row["id"]
        add("admin_product", {"_id": row["id"], "id": row["id"], "sourceNamespace": identity[0], "externalId": identity[1],
                              "versionId": version["id"], "version": version["version"], "snapshot": version["snapshot"],
                              "status": row["status"], "createdAt": date(row["created_at"]), "createdBy": row["created_by"],
                              "updatedAt": date(row["updated_at"]), "updatedBy": row["updated_by"]})
    for (namespace, external), product in sorted(identities.items()):
        add("source_identity", {"_id": hashlib.sha256(json.dumps([namespace, external]).encode()).hexdigest(),
                                "namespace": namespace, "externalId": external, "productId": product})
    for row in tables["admin_product_version"]:
        require("admin_product", row["product_id"])
        audit = next((a for a in tables["admin_audit_event"] if a.get("after_version_id") == row["id"]), {})
        add("admin_product_version", {"_id": row["id"], "id": row["id"], "productId": row["product_id"],
                                      "version": row["version"], "snapshot": row["snapshot"], "createdAt": date(row["created_at"]),
                                      "createdBy": row["created_by"], "requestId": audit.get("request_id"), "action": audit.get("action")})
    for row in tables["admin_audit_event"]:
        require("admin_product", row["product_id"])
        details = dict(row["details"])
        for old, new in (("before_version_id", "beforeVersionId"), ("after_version_id", "afterVersionId")):
            if row.get(old):
                details.setdefault(new, row[old])
        add("admin_audit_event", {"_id": row["id"], "productId": row["product_id"], "action": row["action"],
                                  "actor": row["actor"], "requestId": row["request_id"], "details": details,
                                  "createdAt": date(row["occurred_at"])})
    for row in tables["admin_classification_job"]:
        version = require("admin_product_version", row["version_id"])
        if version["product_id"] != row["product_id"]:
            raise ValueError("Classification version/product mismatch")
        add("admin_classification_job", {"_id": row["id"], "id": row["id"], "productId": row["product_id"],
                                         "versionId": row["version_id"], "status": row["status"], "attempts": row["attempts"],
                                         "input": row["input"], "result": row["result"], "errorCode": row["error_code"],
                                         "inputHash": hashlib.sha256(plain_json(row["input"]).encode()).hexdigest(),
                                         "createdAt": date(row["created_at"]), "finishedAt": date(row["finished_at"]),
                                         "availableAt": int(datetime.fromisoformat(row["available_at"]).timestamp() * 1000)})
    for name in ("admin_label_review", "admin_publication"):
        for row in tables[name]:
            require("admin_product", row["product_id"])
            require("admin_product_version", row["version_id"])
            if name == "admin_label_review":
                require("admin_classification_job", row["job_id"])
            else:
                for field, table in (("review_id", "admin_label_review"), ("release_id", "catalog_release"), ("rule_set_id", "rule_set")):
                    require(table, row[field])
            add(name, {**row, "_id": row["id"], "created_at": date(row["created_at"])})
    active = [row for row in tables["catalog_release"] if row["active"] and row["status"] == "PUBLISHED"]
    if len(active) != 1:
        raise ValueError("Expected exactly one active published catalog")
    catalog = active[0]
    rules = [row for row in tables["rule_set"] if row["catalog_release_id"] == catalog["id"] and row["active"] and row["status"] == "PUBLISHED"]
    if len(rules) != 1:
        raise ValueError("Expected exactly one active rule set for the active catalog")
    rule = rules[0]
    ref = rule["bundle"]["catalog"]
    if ref["fingerprint"] != catalog["fingerprint"] or ref["version"] != catalog["version"] or ref["taxonomyVersion"] != catalog["manifest"]["taxonomyVersion"]:
        raise ValueError("Active catalog/rules pair is inconsistent")
    if not any(row["release_id"] == catalog["id"] for row in tables["product_revision"]):
        raise ValueError("Active catalog is empty")
    add("publication_pointer", {"_id": "active", "catalogReleaseId": catalog["id"], "ruleSetId": rule["id"]})
    # Check the same uniqueness contracts enforced by MongoSchema before any write.
    for name, keys in (("catalog_release", ("version",)), ("rule_set", ("version",)),
                       ("product_revision", ("releaseId", "id")), ("admin_product", ("sourceNamespace", "externalId")),
                       ("admin_product_version", ("productId", "version")), ("admin_publication", ("version_id",))):
        values = [tuple(row[key] for key in keys) for row in out[name]]
        if len(values) != len(set(values)):
            raise ValueError("Duplicate Mongo key in " + name)
    return dict(out)


IMPORT_JS = r'''
const payload = JSON.parse(require('fs').readFileSync(process.env.REPAIRCALC_MIGRATION_INPUT, 'utf8'));
const c = new Mongo('mongodb://127.0.0.1:' + (process.env.MONGO_PORT || '27017') + '/?directConnection=true');
const auth = c.getDB('admin').auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD);
if (auth !== 1 && auth?.ok !== 1) throw Error('Authentication failed');
const d = c.getDB(payload.database);
if (d.getCollectionNames().length) throw Error('Target database must be completely empty');
const canonical = value => {
 if (Array.isArray(value)) return value.map(canonical);
 if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
 return value;
};
const encode = value => JSON.stringify(canonical(EJSON.serialize(value, {relaxed:false})));
const expected = {};
for (const [name, rows] of Object.entries(payload.collections)) {
 expected[name] = rows.map(row => EJSON.deserialize(row));
 if (rows.length) d.getCollection(name).insertMany(expected[name]);
 const actual = d.getCollection(name).find().toArray();
 if (actual.length !== rows.length) throw Error('Count mismatch: ' + name);
 const originals = new Map(expected[name].map(row => [row._id, encode(row)]));
 if (actual.some(row => encode(row) !== originals.get(row._id))) throw Error('Content mismatch: ' + name);
}
print(JSON.stringify({status:'verified', counts:Object.fromEntries(Object.entries(expected).map(([k,v])=>[k,v.length]))}));
'''


def import_command(container):
    # Drain docker's nonblocking stdin into a private regular file before mongosh
    # reads it. MongoDB 7's shell cannot use top-level await in --eval.
    shell = '''set -eu
umask 077
snapshot=$(mktemp /tmp/repaircalc-migration.XXXXXX)
trap 'rm -f "$snapshot"' EXIT
cat > "$snapshot"
export REPAIRCALC_MIGRATION_INPUT="$snapshot"
mongosh --nodb --quiet --eval "$1"
'''
    return ["docker", "exec", "-i", container, "sh", "-c", shell, "migration", IMPORT_JS]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    export = sub.add_parser("export")
    export.add_argument("--container", required=True)
    export.add_argument("--output", type=Path, required=True)
    prepare = sub.add_parser("prepare")
    prepare.add_argument("--snapshot", type=Path, required=True)
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--reference-rules", type=Path, default=Path(__file__).resolve().parents[1] / "data/rules-v1.json")
    prepare.add_argument("--reference-catalog", type=Path, default=Path(__file__).resolve().parents[1] / "data/catalog-v1.json")
    imp = sub.add_parser("import")
    imp.add_argument("--container", required=True)
    imp.add_argument("--prepared", type=Path, required=True)
    imp.add_argument("--database", required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == "export":
            result = export_snapshot(args.container, args.output)
        elif args.action == "prepare":
            source = args.snapshot.read_bytes()
            collections = transform(load_snapshot(source.decode()), args.reference_rules, args.reference_catalog)
            payload = {"sourceSha256": hashlib.sha256(source).hexdigest(),
                       "referenceRulesSha256": hashlib.sha256(args.reference_rules.read_bytes()).hexdigest(),
                       "referenceCatalogSha256": hashlib.sha256(args.reference_catalog.read_bytes()).hexdigest(),
                       "collections": collections}
            private_write(args.output, json.dumps(payload, ensure_ascii=False))
            result = {"sourceSha256": payload["sourceSha256"], "counts": {k: len(v) for k, v in collections.items()}}
        else:
            if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", args.database) or args.database in ("admin", "local", "config"):
                raise ValueError("Use a new application database name")
            payload = json.loads(args.prepared.read_text())
            payload["database"] = args.database
            result = json.loads(run(import_command(args.container), json.dumps(payload)))
        print(json.dumps(result))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        # Raw records and subprocess output must not appear in Actions logs.
        print("Migration refused: " + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
