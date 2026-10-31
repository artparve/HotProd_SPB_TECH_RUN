import json
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import migrate_postgres as migration


def snapshot():
    tables = {name: [] for name in migration.TABLES}
    ref = {"version": "published", "fingerprint": "a" * 64, "taxonomyVersion": "work-taxonomy-v1"}
    tables["catalog_release"] = [{"id": "release", "active": True, "status": "PUBLISHED",
                                  "version": ref["version"], "fingerprint": ref["fingerprint"],
                                  "manifest": {"taxonomyVersion": ref["taxonomyVersion"]}}]
    tables["rule_set"] = [{"id": "rules", "catalog_release_id": "release", "active": True,
                           "status": "PUBLISHED", "version": "rules-v1", "fingerprint": "b" * 64,
                           "bundle": {"catalog": ref, "assignments": [{"productId": "product", "profileId": "profile", "blockedReason": None}]}}]
    tables["product"] = [{"id": "product"}]
    row = {name: None for name in ("package_size", "wet", "high_load", "unheated", "consumption", "roll_width", "roll_length", "rapport")}
    row.update(id="revision", release_id="release", product_id="product", name="Краска", category="paint", kind="paint",
               price=Decimal("123.45"), unit="l", sale_unit="bucket", description="", source="original")
    tables["product_revision"] = [row]
    tables["product_work"] = [{"release_id": "release", "product_id": "product", "work_code": "paint", "evidence": "original evidence"}]
    return tables


class MigrationTests(unittest.TestCase):
    def test_covering_family_requires_matching_reviewed_reference(self):
        tables = snapshot()
        tables['product_revision'][0]['kind'] = 'covering'
        row = tables['product_revision'][0]
        product = {'id': row['product_id'], 'kind': 'covering', 'name': row['name'],
                   'category': row['category'], 'unit': row['unit'], 'saleUnit': row['sale_unit'],
                   'packageSize': row['package_size'], 'materialFamily': 'laminate'}
        with tempfile.TemporaryDirectory() as directory:
            reference = Path(directory)/'catalog.json'
            reference.write_text(json.dumps({'products': [product]}))
            result = migration.transform(tables, reference_catalog=reference)
            self.assertEqual(result['product_revision'][0]['materialFamily'], 'laminate')
            product['name'] = 'Different product'
            reference.write_text(json.dumps({'products': [product]}))
            with self.assertRaisesRegex(ValueError, 'explicitly reviewed reference'):
                migration.transform(tables, reference_catalog=reference)

    def test_import_streams_payload_privately_and_always_removes_temporary_file(self):
        argv = migration.import_command("mongo")
        self.assertEqual(argv[:5], ["docker", "exec", "-i", "mongo", "sh"])
        self.assertIn("umask 077", argv[6])
        self.assertIn('trap \'rm -f "$snapshot"\' EXIT', argv[6])
        self.assertIn('cat > "$snapshot"', argv[6])
        self.assertIn("Target database must be completely empty", argv[-1])

    def test_classification_hash_json_preserves_unicode_and_decimal_precision(self):
        self.assertEqual(migration.plain_json({"name": "Краска", "value": Decimal("0.100000000000000001")}),
                         '{"name":"Краска","value":0.100000000000000001}')

    def test_retains_uuid_active_pair_nulls_exact_money_work_and_original_rows(self):
        tables = snapshot()
        result = migration.transform(tables)
        product = result["product_revision"][0]
        self.assertEqual(product["_id"], "revision")
        self.assertEqual(product["id"], "product")
        self.assertEqual(product["price"], {"$numberDecimal": "123.45"})
        self.assertIsNone(product["wet"])
        self.assertEqual(product["works"], ["paint"])
        self.assertEqual(result["publication_pointer"], [{"_id": "active", "catalogReleaseId": "release", "ruleSetId": "rules"}])
        self.assertEqual(result["rule_set"][0]["bundle"]["assignments"][0]["priority"], 0)
        self.assertEqual(len(result["rule_set"][0]["bundle"]["kits"]), 8)
        self.assertNotIn("priority", tables["rule_set"][0]["bundle"]["assignments"][0])
        self.assertEqual(len(result["legacy_postgres"]), sum(map(len, tables.values())))
        archived = next(row["row"] for row in result["legacy_postgres"] if row["table"] == "product_work")
        self.assertEqual(archived["evidence"], "original evidence")

    def test_rejects_broken_publication_and_foreign_keys(self):
        for change in (lambda t: t["rule_set"][0]["bundle"]["catalog"].update(fingerprint="wrong"),
                       lambda t: t["catalog_release"][0].update(active=False),
                       lambda t: t["product_revision"][0].update(product_id="missing")):
            tables = snapshot()
            change(tables)
            with self.assertRaises(ValueError):
                migration.transform(tables)

    def test_rejects_unhandled_and_missing_source_tables(self):
        for names in (list(migration.TABLES) + ["unknown"], list(migration.TABLES)[:-1]):
            with self.assertRaises(ValueError):
                migration.load_snapshot(json.dumps({"tables": sorted(names)}))

    def test_export_uses_one_read_only_snapshot_and_never_overwrites(self):
        tables = snapshot()
        data = json.dumps({"tables": sorted(tables)}) + "\n" + "\n".join(
            json.dumps({"table": name, "rows": rows}, default=str) for name, rows in tables.items())
        with tempfile.TemporaryDirectory() as directory, patch.object(migration, "run", return_value=data) as run:
            output = Path(directory) / "snapshot.jsonl"
            migration.export_snapshot("postgres", output)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertIn("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY", run.call_args.args[1])
            self.assertNotIn("password", " ".join(run.call_args.args[0]).lower())
            with self.assertRaises(FileExistsError):
                migration.export_snapshot("postgres", output)
            self.assertEqual(output.read_text(), data)

    def test_admin_version_history_dates_and_audit_are_preserved(self):
        tables = snapshot()
        stamp = "2026-10-31T16:00:00+03:00"
        tables["admin_product"] = [{"id": "draft", "source_namespace": "shop", "external_id": "1",
                                    "current_version_id": "v2", "status": "draft", "created_at": stamp,
                                    "updated_at": stamp, "created_by": "admin", "updated_by": "admin"}]
        tables["admin_product_version"] = [{"id": version, "product_id": "draft", "version": number,
                                            "snapshot": {"name": version}, "created_at": stamp, "created_by": "admin"}
                                           for number, version in enumerate(("v1", "v2"), 1)]
        tables["admin_audit_event"] = [{"id": "event", "product_id": "draft", "action": "update", "actor": "admin",
                                        "request_id": "request", "occurred_at": stamp, "details": {},
                                        "before_version_id": "v1", "after_version_id": "v2"}]
        result = migration.transform(tables)
        self.assertEqual(result["admin_product"][0]["versionId"], "v2")
        self.assertEqual(result["admin_product"][0]["createdAt"], {"$date": "2026-10-31T13:00:00.000Z"})
        self.assertEqual(len(result["admin_product_version"]), 2)
        self.assertEqual(result["admin_audit_event"][0]["details"]["beforeVersionId"], "v1")
        self.assertEqual(result["admin_product_version"][1]["requestId"], "request")

    def test_duplicate_or_pending_jobs_cannot_be_silently_dropped_or_started(self):
        tables = snapshot()
        tables["admin_classification_job"] = [{"id": "1", "version_id": "v1"}, {"id": "2", "version_id": "v1"}]
        with self.assertRaisesRegex(ValueError, "Multiple historical"):
            migration.transform(tables)
        tables["admin_product_version"] = [{"id": "v1", "product_id": "product"}]
        tables["admin_classification_job"] = [{"id": "1", "version_id": "v1", "product_id": "product", "status": "queued"}]
        with self.assertRaisesRegex(ValueError, "pending classification"):
            migration.transform(tables)


if __name__ == "__main__":
    unittest.main()
