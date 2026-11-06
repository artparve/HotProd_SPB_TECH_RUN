import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from repaircalc_classifier import audit
from repaircalc_classifier.schema import TAXONOMY


class CatalogAuditTests(unittest.TestCase):
    def test_offline_audit_uses_the_current_taxonomy_and_writes_both_source_inventories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_json = root / "catalog.json"
            source_xml = root / "catalog.xml"
            output = root / "audit"
            source_json.write_text(json.dumps({"products": [{
                "id": "json-1", "cod_good": "42", "name": "Краска для стен",
                "categories": {"7": "Краски"},
                "characteristics": [{"name": "Тип поверхности", "value": "бетон"}],
            }]}, ensure_ascii=False), encoding="utf-8")
            source_xml.write_text('''<yml_catalog><shop><categories>
                <category id="8">Напольные покрытия</category></categories><offers>
                <offer id="xml-1"><name>Ламинат</name><categoryId>8</categoryId>
                </offer></offers></shop></yml_catalog>''', encoding="utf-8")
            args = ["catalog-audit", "--json-catalog", str(source_json),
                    "--xml-catalog", str(source_xml), "--output", str(output)]
            with patch("sys.argv", args), contextlib.redirect_stdout(io.StringIO()):
                audit.main()

            summary = json.loads((output / "dataset_summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["taxonomy_version"], TAXONOMY["version"])
            self.assertEqual(summary["json"]["products"], 1)
            self.assertEqual(summary["xml"]["products"], 1)
            self.assertEqual(summary["xml"]["all_category_ids"], 1)
            self.assertEqual(summary["json"]["sha256"], audit.fingerprint(source_json))
            categories = (output / "source_categories.tsv").read_text(encoding="utf-8")
            self.assertIn("json\t7\tКраски\t1", categories)
            self.assertIn("xml\t8\tНапольные покрытия\t1", categories)
            support = json.loads((output / "taxonomy_support.json").read_text(encoding="utf-8"))
            self.assertEqual(set(support), {"work_types", "surface_locations", "substrates", "properties"})


if __name__ == "__main__":
    unittest.main()
