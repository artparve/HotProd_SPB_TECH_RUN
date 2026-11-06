#!/usr/bin/env python3
"""Build a reproducible educational catalog; never connect to a database."""

import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import uuid

ROOT = Path(__file__).resolve().parents[1]
ROOMS = (
    ("living", "гостиная", False),
    ("bedroom", "спальня", False),
    ("children", "детская", False),
    ("kitchen", "кухня", True),
    ("bathroom", "ванная", True),
)
STYLES = (
    ("scandinavian", "скандинавский", "светлый дуб", "молочный", "лён"),
    ("loft", "лофт", "тёмный бетон", "графитовый", "кирпич"),
    ("minimalism", "минимализм", "светлый камень", "жемчужный", "геометрия"),
    ("neoclassical", "неоклассика", "тёплый мрамор", "кремовый", "дамаск"),
    ("japandi", "джапанди", "натуральный ясень", "песочный", "бамбук"),
)


def text(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def build():
    data = ROOT / "data"
    catalog = json.loads((data / "catalog-v1.json").read_text(encoding="utf-8"))
    mappings = json.loads((data / "work-mappings-v1.json").read_text(encoding="utf-8"))
    rules = json.loads((data / "rules-v1.json").read_text(encoding="utf-8"))
    templates = catalog["products"]
    assignments = {a["productId"]: a for a in rules["assignments"]}
    work_by_id = {m["productId"]: m["works"] for m in mappings["mappings"]}
    catalog["version"] = "synthetic-rooms-catalog-v1"
    mappings["version"] = "synthetic-rooms-mappings-v1"
    rules["version"] = "synthetic-rooms-rules-v1"
    for room_index, (room, room_name, wet) in enumerate(ROOMS):
        for style_index, (style, style_name, floor, color, pattern) in enumerate(STYLES):
            # Wet rooms use tile + adhesive; dry rooms also offer wallpaper.
            # All compatibility and consumption figures remain explicitly synthetic.
            floor_index = 1 if wet or style in ("loft", "neoclassical") else (2 if room == "children" else 0)
            variants = (
                (floor_index, f"Покрытие «{floor}»"),
                (4 if wet else 3, f"Краска «{color}»"),
                (7 if wet else 6, "Клей плиточный" if wet else f"Обои «{pattern}»"),
                (5, "Грунтовка интерьерная"),
            )
            for variant_index, (template_index, label) in enumerate(variants):
                original = templates[template_index]
                item = copy.deepcopy(original)
                number = room_index * 20 + style_index * 4 + variant_index + 1
                external_id = f"rooms-{room}-{style}-{item['kind']}"
                item["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, "repaircalc.synthetic/" + external_id))
                item["name"] = f"{label} — {room_name}, {style_name}"
                # Integer arithmetic: prices have exactly two decimal places.
                cents = int(Decimal(original["price"]) * 100) + number * 137
                item["price"] = f"{cents // 100}.{cents % 100:02d}"
                item["description"] = (
                    f"ТЕСТОВЫЕ данные. Комната: {room_name}. Стиль: {style_name}. "
                    + original["description"]
                    + " Стиль и цвет — описание, не подтверждение совпадения с фотографией."
                )
                item["source"] = "ТЕСТОВЫЕ товары, цены, совместимость и нормы; synthetic-rooms-catalog-v1."
                raw = copy.deepcopy(item)
                raw.pop("sources", None)
                raw.update(roomType=room, style=style, educational=True)
                item["sources"] = [{"namespace": "repaircalc.synthetic-rooms", "externalId": external_id, "raw": raw}]
                catalog["products"].append(item)
                mappings["mappings"].append({
                    "productId": item["id"], "works": work_by_id[original["id"]],
                    "evidence": "Manual educational variant; inherited synthetic work mapping, not manufacturer data.",
                })
                assignment = copy.deepcopy(assignments[original["id"]])
                assignment.update(productId=item["id"], priority=100 + number)
                rules["assignments"].append(assignment)
    catalog_text, mapping_text = text(catalog), text(mappings)
    rules["catalog"].update(
        version=catalog["version"],
        fingerprint=sha("prepared-catalog-v1\n" + sha(catalog_text) + "\n" + sha(mapping_text)),
    )
    return {"catalog-expanded.json": catalog_text, "work-mappings-expanded.json": mapping_text,
            "rules-expanded.json": text(rules)}


if __name__ == "__main__":
    for name, content in build().items():
        with (ROOT / "data" / name).open("w", encoding="utf-8", newline="\n") as output:
            output.write(content)
    print("Generated 112 educational products (12 original + 100 new), mappings and matching rules.")
