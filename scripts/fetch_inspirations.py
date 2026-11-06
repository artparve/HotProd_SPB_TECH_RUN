#!/usr/bin/env python3
"""Explicit, build-time download of curated Pexels photos; no API key or DB writes."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "data/inspirations"
MARKER = "<!-- additional-photos -->"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Allow explicit network downloads of missing files")
    args = parser.parse_args()
    additions = json.loads((ASSETS / "additions.json").read_text(encoding="utf-8"))
    manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
    ids = {entry["id"] for entry in manifest}
    credits = [MARKER, "## Дополнение от 06.11.2026", "",
               "Ещё 8 фотографий Max Vakhtbovych с Pexels. Лицензия и условия использования — те же, что выше.", "",
               "| Файл | Исходная страница | SHA-256 | Размер |", "| --- | --- | --- | --- |"]
    for entry in additions:
        match = re.fullmatch(r"(?:living-room|bedroom|kitchen|bathroom)-(\d+)\.jpg", entry["file"])
        if not match or not entry["sourceUrl"].startswith("https://www.pexels.com/photo/"):
            raise ValueError("Invalid curated photo metadata")
        path = ASSETS / entry["file"]
        if not path.exists():
            if not args.download:
                raise SystemExit("Photo missing; run again with --download to permit network access")
            photo_id = match[1]
            url = f"https://images.pexels.com/photos/{photo_id}/pexels-photo-{photo_id}.jpeg?auto=compress&cs=tinysrgb&w=1440"
            request = urllib.request.Request(url, headers={"User-Agent": "RepairCalc-curated-gallery/1.0"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read(8 * 1024 * 1024 + 1)
            if not data.startswith(b"\xff\xd8\xff") or len(data) > 8 * 1024 * 1024:
                raise ValueError("Expected a JPEG within the image size limit")
            # Exclusive creation never overwrites an operator's photo.
            with path.open("xb") as output:
                output.write(data)
        data = path.read_bytes()
        if not data.startswith(b"\xff\xd8\xff") or len(data) > 8 * 1024 * 1024:
            raise ValueError("Invalid local photo")
        if entry["id"] not in ids:
            manifest.append(entry)
            ids.add(entry["id"])
        elif next(item for item in manifest if item["id"] == entry["id"]) != entry:
            raise ValueError("Existing manifest ID has different metadata")
        credits.append(f"| {entry['file']} | [Pexels]({entry['sourceUrl']}) | `{hashlib.sha256(data).hexdigest()}` | {len(data)} |")
    with (ASSETS / "manifest.json").open("w", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    prior = (ASSETS / "CREDITS.md").read_text(encoding="utf-8").split(MARKER)[0].rstrip()
    with (ASSETS / "CREDITS.md").open("w", encoding="utf-8", newline="\n") as output:
        output.write(prior + "\n\n" + "\n".join(credits) + "\n")
    print(f"Gallery prepared: {len(manifest)} photos. Mongo metadata is imported by init-data, not this script.")


if __name__ == "__main__":
    main()
