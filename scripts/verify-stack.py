#!/usr/bin/env python3
"""Build an isolated Docker stack, test real APIs and outages, then remove only its volumes.

By default only the Ollama transport is replaced. --runtime-only uses official Ollama without a model.
No local .env or secret.env is read; all credentials and assets are disposable fixtures.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import shutil
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPCookieProcessor
import uuid
import zlib
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.mongo_runtime import image_reference, check as check_mongo, write_report


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x80\x90\xa0" * 8) * 8)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--playwright", action="store_true", help="Also run real browser API-mode scenarios")
    parser.add_argument("--no-build", action="store_true", help="Use existing local application images")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--base-only", action="store_true", help="Verify bundled production photos and baseline without Python/Ollama/token")
    modes.add_argument("--runtime-only", action="store_true", help="Verify full production profile with official Ollama and no installed model")
    parser.add_argument("--maven-repository", default="https://repo.maven.apache.org/maven2", help="Public Maven Central endpoint or mirror for Docker build")
    args = parser.parse_args()
    mongo_image = image_reference()
    compatibility = check_mongo(mongo_image,
                                seconds=int(os.environ.get("MONGO_COMPAT_SECONDS", "95")),
                                startup_timeout=int(os.environ.get("MONGO_COMPAT_START_TIMEOUT_SECONDS", "60")))
    report = ".build-mongo-compat-base.json" if args.base_only else ".build-mongo-compat-runtime.json" if args.runtime_only else ".build-mongo-compat-stack.json"
    write_report(ROOT / report, compatibility)
    if compatibility["status"] != "ok":
        raise RuntimeError("Mongo compatibility failed: " + compatibility["error_code"])
    print("PASS isolated Mongo startup, transaction and " + str(compatibility["stable_seconds"]) + "s CRUD stability", flush=True)
    project = "repaircalc_verify_" + uuid.uuid4().hex[:10]
    with socket.socket() as bound:
        bound.bind(("127.0.0.1", 0))
        port = bound.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="repaircalc-verify-") as directory:
        temporary = Path(directory)
        assets = temporary / "inspirations"
        assets.mkdir()
        (assets / "empty.json").write_text("[]", encoding="utf-8")
        prepared_photo = json.loads((ROOT / "data/inspirations/manifest.json").read_text(encoding="utf-8"))[0]
        (assets / "image.jpg").write_bytes((ROOT / "data/inspirations" / prepared_photo["file"]).read_bytes())
        (assets / "manifest.json").write_text(json.dumps([{
            "id": "test-interior", "title": "Тестовый интерьер", "description": "Только фикстура сквозного теста",
            "file": "image.jpg", "sourceUrl": prepared_photo["sourceUrl"], "category": prepared_photo["category"]
        }], ensure_ascii=False), encoding="utf-8")
        password = secrets.token_hex(24)
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600000)
        admin_hash = "pbkdf2-sha256$600000$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()
        public = temporary / "public.env"
        if any(char in args.maven_repository for char in "\r\n"):
            raise ValueError("Maven repository must be a single-line URL")
        public.write_text("MONGO_IMAGE_TAG=" + mongo_image.removeprefix("mongo:") + "\nMAVEN_CENTRAL_URL=" + args.maven_repository + "\nFRONTEND_PUBLIC_PORT=" + str(port) + "\nFRONTEND_BIND_ADDRESS=127.0.0.1\nADMIN_COOKIE_SECURE=false\nADMIN_USERNAME=smoke-admin\nCLASSIFICATION_POLL_MS=250\nHEALTH_INTERVAL=2s\nHEALTH_RETRIES=60\n", encoding="utf-8")
        secret = temporary / "secret.env"
        secret.write_text("\n".join(key + "='" + value + "'" for key, value in {
            "MONGO_ROOT_PASSWORD": secrets.token_hex(24), "MONGO_PASSWORD": secrets.token_hex(24),
            "MONGO_KEYFILE_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
            "INTERNAL_SERVICE_TOKEN": "" if args.base_only else secrets.token_hex(32), "ADMIN_PASSWORD_HASH": admin_hash}.items()) + "\n", encoding="utf-8")
        secret.chmod(0o600)
        asset = {"volumes": [str(assets).replace("\\", "/") + ":/app/test-inspirations:ro"],
                 "environment": {"INSPIRATIONS_DIR": "/app/test-inspirations", "INSPIRATIONS_MANIFEST": "/app/test-inspirations/manifest.json"}}
        override = temporary / "override.json"
        override.write_text(json.dumps({"services": {
            "backend": {} if args.base_only or args.runtime_only else asset,
            "init-data": {} if args.base_only or args.runtime_only else asset,
            "ollama": {"cpus": "0.5", "mem_limit": "512m", "memswap_limit": "512m"} if args.runtime_only else {
                       "image": "repaircalc-llm:local", "entrypoint": ["python", "/app/llm/tests/fake_ollama.py"],
                       "cpus": "0.5", "mem_limit": "512m", "memswap_limit": "512m",
                       "healthcheck": {"test": ["CMD", "python", "-c", "import urllib.request;urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=2).read()"]}}
        }}), encoding="utf-8")
        env = dict(os.environ)
        # A developer's process settings must not redirect this test to their database/ports.
        for line in (ROOT / "env/.env.example").read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#") and "=" in line:
                env.pop(line.split("=", 1)[0], None)
        for key in ("MONGO_ROOT_PASSWORD", "MONGO_PASSWORD", "MONGO_KEYFILE_SECRET", "INTERNAL_SERVICE_TOKEN", "ADMIN_PASSWORD_HASH", "COMPOSE_FILE", "COMPOSE_PROFILES", "IMAGE_TAG"):
            env.pop(key, None)
        compose = ["docker", "compose", "--project-name", project, "--env-file", str(public), "--env-file", str(secret),
                   "-f", str(ROOT / "docker/docker-compose.yml"), "-f", str(override)]
        if not args.base_only:
            compose += ["--profile", "llm"]

        def run(*arguments, capture=False):
            result = subprocess.run(compose + list(arguments), cwd=ROOT, env=env,
                                    capture_output=capture, text=True, encoding="utf-8", errors="replace")
            if result.returncode:
                raise RuntimeError("Compose command failed: " + arguments[0])
            return result

        base = f"http://127.0.0.1:{port}"
        opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def request(path, body=None, method=None, headers=None, expected=200):
            payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
            req = Request(base + path, data=payload, method=method, headers={"Content-Type": "application/json", **(headers or {})})
            try:
                response = opener.open(req, timeout=35)
            except HTTPError as error:
                response = error
            with response:
                data = response.read()
                if response.status != expected:
                    raise AssertionError(f"{path}: expected {expected}, received {response.status}")
                result = json.loads(data) if data and response.headers.get_content_type() == "application/json" else data
                return result, response.headers

        def wait(path, check=lambda body: True, timeout=90):
            deadline = time.monotonic() + timeout
            while True:
                try:
                    value, _ = request(path)
                    if check(value):
                        return value
                except (OSError, URLError, AssertionError):
                    pass
                if time.monotonic() >= deadline:
                    raise AssertionError("Readiness timeout: " + path)
                time.sleep(1)

        def browser_check(outage=None, production=False):
            if not args.playwright:
                return
            browser_env = dict(env, REPAIRCALC_STACK_URL=base)
            browser_env.pop("REPAIRCALC_OUTAGE", None)
            browser_env.pop("REPAIRCALC_PRODUCTION_GALLERY", None)
            if outage:
                browser_env["REPAIRCALC_OUTAGE"] = outage
            if production:
                browser_env["REPAIRCALC_PRODUCTION_GALLERY"] = "1"
            cli = ROOT / "frontend/node_modules/@playwright/test/cli.js"
            selected = ["tests/stack/gallery.spec.ts"] if production else []
            subprocess.run(["node", str(cli), "test", *selected, "--config", "playwright.stack.config.ts"],
                           cwd=ROOT / "frontend", env=browser_env, check=True)

        try:
            if not args.no_build:
                run("build", "backend", "frontend", *([] if args.base_only else ["llm"]))
            run("up", "--detach", "--no-build", "--wait", "--wait-timeout", "240", "backend", "frontend", *([] if args.base_only else ["llm", "ollama"]))
            catalog = wait("/api/v1/products", lambda value: len(value) == 12)
            # Exercise the release preflight against real network authentication without
            # using any developer configuration or changing running services.
            release = temporary / "preflight"
            (release / "docker").mkdir(parents=True)
            (release / "env").mkdir()
            (release / "scripts").mkdir()
            shutil.copyfile(ROOT / "scripts/mongo_runtime.py", release / "scripts/mongo_runtime.py")
            for filename in ("docker-compose.yml", "docker-compose.release.yml"):
                shutil.copyfile(ROOT / "docker" / filename, release / "docker" / filename)
            shutil.copyfile(public, release / "env/.env")
            shutil.copyfile(secret, release / "env/secret.env")
            preflight_env = dict(env, IMAGE_PREFIX="ghcr.io/repaircalc/test", IMAGE_TAG="sha-" + "a" * 40)
            preflight = [sys.executable, str(ROOT / "scripts/deploy_target.py"), "plan", "--path", str(release),
                         "--base", str(release), "--project", project, "--environment", "staging", "--host", "127.0.0.1", "--port", str(port)]
            checked = subprocess.run(preflight, cwd=ROOT, env=preflight_env, capture_output=True, text=True, check=False)
            assert checked.returncode == 0, checked.stdout
            verified = json.loads(checked.stdout)
            assert verified["existing_database"] and verified["preserve_catalog"] and verified["volume"] == project + "_mongo-data"
            denied = subprocess.run(preflight, cwd=ROOT, env=dict(preflight_env, MONGO_PASSWORD="invalid-test-password"), capture_output=True, text=True, check=False)
            assert denied.returncode == 1 and "credentials" in json.loads(denied.stdout)["error"]
            print("PASS deployment preflight and rejection of invalid network credentials", flush=True)
            assert all("demo-" not in item["id"] for item in catalog)
            tile = "ac1f222a-f5e9-3b5d-a91e-c943b053e92e"
            room = {"id": "room-a", "name": "Комната", "type": "living", "length": 5, "width": 4, "height": 2.7,
                    "openings": 3.5, "humidity": "normal", "load": "normal", "heated": True,
                    "works": ["floor", "paint"], "reserve": 10, "layers": 2, "sockets": 0, "cableLength": 0}
            project_body = {"object": "apartment", "renovation": "capital", "rooms": [room]}
            inputs = {"area": 20, "perimeter": 18, "height": 2.7, "reserve": 10, "layers": 2, "quantity": 6}
            if args.base_only or args.runtime_only:
                if args.runtime_only:
                    run("exec", "-T", "llm", "python", "-c",
                        "import json,os,urllib.request; result=json.load(urllib.request.urlopen(os.environ['OLLAMA_URL']+'/api/tags',timeout=10)); assert result['models']==[]")
                feed, _ = request("/api/v1/room-inspirations")
                prepared = json.loads((ROOT / "data/inspirations/manifest.json").read_text(encoding="utf-8"))
                assert prepared and feed["status"] == "ok"
                assert {item["id"] for item in feed["items"]} == {item["id"] for item in prepared}
                from collections import Counter
                assert Counter(item["category"] for item in feed["items"]) == {"living": 5, "bedroom": 5, "kitchen": 5, "bathroom": 5}
                for entry in prepared:
                    item = next(item for item in feed["items"] if item["id"] == entry["id"])
                    assert item["sourceUrl"] == entry["sourceUrl"]
                    assert item["category"] == entry["category"]
                    original, _ = request(item["imageUrl"])
                    assert original == (ROOT / "data/inspirations" / entry["file"]).read_bytes()
                    preview, _ = request(item["thumbnailUrl"])
                    assert preview.startswith(b"\x89PNG")
                unavailable, _ = request("/api/v1/room-inspirations/" + prepared[0]["id"] + "/analyze", {"project": project_body})
                assert unavailable["status"] == "unavailable" and unavailable["selections"] == {}
                assert unavailable["project"] == project_body
                result, _ = request("/api/v1/materials/estimate", {"productId": tile, "input": inputs})
                assert result["explanationStatus"] == "unavailable" and result["estimate"]["total"] == "34560.00"
                request("/")
                mode = "with official Ollama/Python and no installed model" if args.runtime_only else "without Python, Ollama or token"
                print("PASS all 20 production photos, categories, previews and unavailable analysis " + mode, flush=True)
                browser_check(production=True)
                return
            estimate, _ = request("/api/v1/estimate", {"project": project_body, "selections": {"room-a:covering": tile}})
            assert estimate["mode"] == "api" and len(estimate["lines"]) == 4 and estimate["labor"] == "33295.00"
            feed, _ = request("/api/v1/room-inspirations")
            assert len(feed["items"]) == 1
            image, _ = request(feed["items"][0]["thumbnailUrl"])
            assert image.startswith(b"\x89PNG")
            analysis, _ = request("/api/v1/room-inspirations/test-interior/analyze", {"project": project_body})
            assert analysis["status"] == "ok" and analysis["project"]["rooms"][0]["length"] == 5
            explanation, _ = request("/api/v1/materials/estimate", {"productId": tile, "input": inputs})
            assert explanation["explanationStatus"] == "unverified_model_text"
            plan, _ = request("/api/v1/plans/extract", {"imageBase64": base64.b64encode(png()).decode()})
            assert plan["requiresConfirmation"] is True and plan["schemaVersion"] == "room-plan-draft-v1"
            print("PASS real catalog, estimate, image and Python explanation", flush=True)

            browser_check()

            # Exercise admin authentication, optimistic locking and Java -> Python classification.
            session, _ = request("/api/v1/admin/session")
            session, _ = request("/api/v1/admin/login", {"username": "smoke-admin", "password": password},
                                 headers={"X-CSRF-Token": session["csrfToken"]})
            csrf = {"X-CSRF-Token": session["csrfToken"]}
            draft = {"sourceNamespace": "stack-test", "externalId": str(uuid.uuid4()), "name": "Учебная краска", "description": "Краска для стен", "sources": [{"label": "Тестовая этикетка"}]}
            for key, value in {"category": "Краски", "kind": "paint", "price": "1234.50", "currency": "RUB", "priceDate": "2026-10-30", "unit": "л", "saleUnit": "бан.", "packageSize": 2.5, "consumption": 0.12, "consumptionUnit": "л"}.items():
                draft[key] = {"state": "confirmed", "value": value, "source": "Тестовая этикетка"}
            product, headers = request("/api/v1/admin/products", draft, headers=csrf, expected=201)
            product_path = "/api/v1/admin/products/" + product["id"]
            version = dict(csrf, **{"If-Match": headers["ETag"]})
            request(product_path, draft, method="PUT", headers=dict(csrf, **{"If-Match": '"stale"'}), expected=412)
            request(product_path + "/classifications", {}, headers=version, expected=202)
            jobs = wait(product_path + "/classifications", lambda value: value and value[0]["status"] in {"succeeded", "failed"})
            job = jobs[0]
            assert job["status"] == "succeeded", "Classification failed: " + str(job["errorCode"])
            classification = job["result"]["classification"]
            decisions = [{"group": group, "label": label["label"], "action": "accept", "reason": "Проверено в сквозном тесте"}
                         for group in ("work_types", "surface_locations", "substrates") for label in classification[group]]
            decisions += [{"group": "properties", "label": label, "action": "accept", "reason": "Сохранено как неизвестное"} for label in classification["properties"]]
            request(product_path + "/reviews", {"jobId": job["id"], "profileId": "paint-012-v1", "decisions": decisions}, headers=version)
            request(product_path + "/publish", {}, headers=version)
            assert len(request("/api/v1/products")[0]) == 13
            run("run", "--rm", "--no-deps", "init-data")
            assert len(request("/api/v1/products")[0]) == 13
            print("PASS admin session, ETag, queue, human review, publication and repeat seed", flush=True)

            # Reproduce an existing production database with an empty/old gallery.
            published_catalog, _ = request("/api/v1/products")
            run("run", "--rm", "--no-deps", "-e", "INSPIRATIONS_MANIFEST=/app/test-inspirations/empty.json",
                "--entrypoint", "java", "init-data", "-jar", "/app/backend.jar", "--repaircalc.command=import-inspirations")
            assert request("/api/v1/room-inspirations")[0]["items"] == []
            subprocess.run(compose + ["-f", str(ROOT / "docker/docker-compose.preserve-data.yml"),
                           "run", "--rm", "--no-deps", "init-data"], cwd=ROOT, env=env, check=True)
            assert request("/api/v1/room-inspirations")[0]["items"][0]["id"] == "test-interior"
            assert request("/api/v1/products")[0] == published_catalog
            print("PASS existing-database release refreshes the gallery and preserves the published catalog", flush=True)

            backup = temporary / "mongo.archive.gz"
            backup_command = [sys.executable, str(ROOT / "scripts/mongo-backup.py"),
                              "--project-name", project, "--env-file", str(public), "--env-file", str(secret),
                              "--compose-file", str(ROOT / "docker/docker-compose.yml"), "--compose-file", str(override),
                              "--file", str(backup)]
            subprocess.run(backup_command + ["backup"], cwd=ROOT, env=env, check=True)
            wait("/api/v1/products", lambda value: len(value) == 13)
            subprocess.run(backup_command + ["restore", "--target-database", "repaircalc_restore_check"], cwd=ROOT, env=env, check=True)
            print("PASS archive backup and restore into a new database", flush=True)
            session, _ = request("/api/v1/admin/session")
            session, _ = request("/api/v1/admin/login", {"username": "smoke-admin", "password": password},
                                 headers={"X-CSRF-Token": session["csrfToken"]})
            csrf = {"X-CSRF-Token": session["csrfToken"]}

            run("stop", "ollama")
            browser_check("ollama")
            result, _ = request("/api/v1/materials/estimate", {"productId": tile, "input": inputs})
            assert result["explanationStatus"] == "unavailable" and result["estimate"]["total"] == "34560.00"
            run("start", "ollama")
            run("stop", "llm")
            browser_check("llm")
            result, _ = request("/api/v1/materials/estimate", {"productId": tile, "input": inputs})
            assert result["explanationStatus"] == "unavailable" and result["estimate"]["total"] == "34560.00"
            result, _ = request("/api/v1/room-inspirations/test-interior/analyze", {"project": project_body})
            assert result["status"] == "unavailable" and result["selections"] == {} and result["project"] == project_body
            run("stop", "mongo")
            browser_check("mongo")
            result, headers = request("/api/v1/products")
            assert result == [] and headers.get("X-RepairCalc-Degraded") == "catalog_unavailable"
            request("/api/v1/estimate", {"project": project_body}, expected=503)
            request("/api/v1/admin/products", draft, headers=csrf, expected=503)
            run("start", "mongo")
            wait("/api/v1/products", lambda value: len(value) == 13)
            run("stop", "backend")
            browser_check("backend")
            result, _ = request("/api/v1/products", expected=503)
            assert result["code"] == "backend_unavailable"
            request("/")
            print("PASS LLM, Mongo and Java outages, recovery and persistent data", flush=True)
        except Exception:
            subprocess.run(compose + ["logs", "--no-color", "--tail", "80", "mongo", "mongo-init", "init-data", "backend", "llm", "frontend", "ollama"],
                           cwd=ROOT, env=env, check=False)
            raise
        finally:
            if not project.startswith("repaircalc_verify_"):
                raise RuntimeError("Refusing cleanup outside isolated verification project")
            subprocess.run(compose + ["down", "--volumes", "--remove-orphans"], cwd=ROOT, env=env, check=False)


if __name__ == "__main__":
    main()
