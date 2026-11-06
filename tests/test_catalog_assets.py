import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("generate_catalog", ROOT / "scripts/generate_catalog.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CatalogAssetsTest(unittest.TestCase):
    def test_generated_files_are_current_and_reproducible(self):
        for name, content in generator.build().items():
            self.assertEqual((ROOT / "data" / name).read_text(encoding="utf-8"), content, name)

    def test_one_hundred_variants_have_unique_identity_mappings_and_profiles(self):
        files = {name: json.loads(content) for name, content in generator.build().items()}
        products = files["catalog-expanded.json"]["products"]
        self.assertEqual(len(products), 112)
        self.assertEqual(len({p["id"] for p in products}), 112)
        self.assertEqual(len({p["name"] for p in products}), 112)
        new = products[12:]
        self.assertEqual(len(new), 100)
        self.assertEqual({p["sources"][0]["raw"]["roomType"] for p in new}, {r[0] for r in generator.ROOMS})
        self.assertEqual({p["sources"][0]["raw"]["style"] for p in new}, {s[0] for s in generator.STYLES})
        assignments = {a["productId"]: a for a in files["rules-expanded.json"]["assignments"]}
        mapped = {m["productId"] for m in files["work-mappings-expanded.json"]["mappings"]}
        for p in new:
            self.assertIn(p["id"], mapped)
            self.assertIsNotNone(assignments[p["id"]]["profileId"])
            self.assertIn("ТЕСТОВЫЕ", p["source"])
            if p["sources"][0]["raw"]["roomType"] in ("kitchen", "bathroom"):
                self.assertTrue(p["wet"])


if __name__ == "__main__":
    unittest.main()
