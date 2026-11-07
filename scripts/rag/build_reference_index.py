#!/usr/bin/env python3
"""Offline, explicitly experimental PDF index. Never creates SKU facts or approvals."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

SCHEMA = 1
CHUNKING = "pdf-layout-page-v1"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def page_chunks(text, limit=1200):
    # Keep page/line context. Extracted tables are text, never interpreted as typed facts.
    lines = [line.rstrip() for line in text.splitlines()]
    current = []
    for line in lines:
        for offset in range(0, max(1, len(line)), limit):
            part = line[offset:offset + limit]
            if current and sum(len(x) + 1 for x in current) + len(part) > limit:
                value = "\n".join(current).strip()
                if value:
                    yield value
                overlap = current[-2:]
                current = overlap if sum(len(x) + 1 for x in overlap) + len(part) <= limit else []
            current.append(part)
    value = "\n".join(current).strip()
    if value:
        yield value


def build(manifests, output, allow_draft=False):
    if not allow_draft:
        raise ValueError("Research corpus requires explicit --allow-draft; no production approval is inferred")
    documents, chunks, skipped = [], [], []
    ids = set()
    for manifest in manifests:
        manifest = Path(manifest).resolve()
        rows = json.loads(manifest.read_text(encoding="utf-8"))
        for row in rows:
            source_id = row["id"]
            if row.get("status") != "downloaded" or not row.get("file"):
                skipped.append({"id": source_id, "reason": "not_downloaded"})
                continue
            if source_id in ids:
                raise ValueError("Duplicate source ID: " + source_id)
            ids.add(source_id)
            path = (manifest.parent / row["file"]).resolve()
            if not path.is_relative_to(manifest.parent) or path.suffix.lower() != ".pdf":
                raise ValueError("Invalid PDF path: " + source_id)
            if path.stat().st_size > 32 * 1024 * 1024 or path.read_bytes()[:5] != b"%PDF-":
                raise ValueError("Invalid PDF size/header: " + source_id)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != row.get("sha256"):
                raise ValueError("PDF checksum mismatch: " + source_id)
            process = subprocess.run(["pdftotext", "-layout", "-enc", "UTF-8", str(path), "-"],
                                     capture_output=True, timeout=90, check=False)
            if process.returncode or len(process.stdout) > 16 * 1024 * 1024:
                raise ValueError("PDF extraction failed: " + source_id)
            pages = process.stdout.decode("utf-8", errors="strict").split("\f")
            if pages and not pages[-1].strip():
                pages.pop()
            url = row.get("source_url") or row.get("url") or row.get("source_page_url")
            if url is not None and (not isinstance(url, str) or not url.startswith("https://")):
                raise ValueError("Invalid provenance URL: " + source_id)
            document = {"id": source_id, "title": row["title"], "publisher": row["publisher"],
                        "url": url, "sha256": digest, "version": row.get("document_version") or "sha256:" + digest,
                        "retrievedOn": row.get("retrieved_on"), "versionNote": row.get("version_note", ""), "tags": row.get("tags", []),
                        "reviewStatus": row.get("review_status", "draft"),
                        "rightsStatus": row.get("rights_status", "not_assessed"),
                        "productionIndexApproved": row.get("production_index_approved") is True,
                        "pages": len(pages), "emptyPages": []}
            for page, text in enumerate(pages, 1):
                if not text.strip():
                    document["emptyPages"].append(page)
                    continue
                for number, chunk in enumerate(page_chunks(text), 1):
                    chunks.append({"id": f"{source_id}:{digest[:12]}:p{page}:c{number}",
                                   "sourceId": source_id, "page": page, "text": chunk})
            documents.append(document)
    if not chunks or len(chunks) > 20000:
        raise ValueError("Empty or oversized reference corpus")
    corpus = {"schemaVersion": SCHEMA, "mode": "research_draft", "documents": documents, "chunks": chunks}
    payload = canonical(corpus)
    if len(payload) > 16 * 1024 * 1024:
        raise ValueError("Reference index exceeds 16 MiB")
    digest = hashlib.sha256(payload).hexdigest()
    manifest = {"schemaVersion": SCHEMA, "mode": "research_draft", "indexId": digest,
                "corpusSha256": digest, "retrievalVersion": "bm25-v1", "chunkingVersion": CHUNKING,
                "normalizationVersion": "unicode-lower-yo-v1", "embeddingModel": "none", "dimension": 0,
                "documentCount": len(documents), "chunkCount": len(chunks), "productLinks": 0,
                "usableForDecisions": False, "skipped": skipped}
    # Each version lives in its own immutable directory. Point RAG_REFERENCE_INDEX at that directory.
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    version = output / digest
    if version.exists():
        if (version / "corpus.json").read_bytes() != payload or (version / "manifest.json").read_bytes() != canonical(manifest):
            raise ValueError("Existing index does not match checksum")
        return version, manifest
    with tempfile.TemporaryDirectory(prefix=".build-", dir=output) as temporary:
        staged = Path(temporary)
        (staged / "corpus.json").write_bytes(payload)
        (staged / "manifest.json").write_bytes(canonical(manifest))
        staged.rename(version)
    return version, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", action="append", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--allow-draft", action="store_true")
    args = parser.parse_args()
    version, manifest = build(args.manifest, args.output, args.allow_draft)
    print(json.dumps({"directory": str(version), **manifest}, ensure_ascii=False))


if __name__ == "__main__":
    main()
