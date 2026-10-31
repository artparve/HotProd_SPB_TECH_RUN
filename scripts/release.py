#!/usr/bin/env python3
"""Deploy previously built images through Ansible; never build or publish locally."""

import argparse
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]

# Only configurable runtime settings used by the release Compose stack.
# Frontend build settings, upstream service names and image references are fixed by deployment.
# Runtime defaults are shared with local development and Compose.
# This tracked example contains no credentials. Process env overrides each setting.
DEPLOYMENT_PUBLIC_DEFAULTS = {}
for _line in (ROOT / 'env/.env.example').read_text(encoding='utf-8').splitlines():
    if _line and not _line.lstrip().startswith('#') and '=' in _line:
        _name, _value = _line.split('=', 1)
        if not _name.startswith(('VITE_', 'DEV_', 'LOCAL_')) and _name not in {'MONGO_URI', 'API_UPSTREAM', 'LLM_SERVICE_URL', 'SERVER_ADDRESS', 'SERVER_PORT', 'SPRING_PROFILES_ACTIVE'}:
            DEPLOYMENT_PUBLIC_DEFAULTS[_name] = _value
DEPLOYMENT_SECRET_DEFAULTS = {'MONGO_ROOT_PASSWORD': None, 'MONGO_PASSWORD': None, 'MONGO_KEYFILE_SECRET': None,
                              'INTERNAL_SERVICE_TOKEN': '', 'ADMIN_PASSWORD_HASH': ''}
DEPLOYMENT_ENV_DEFAULTS = {**DEPLOYMENT_PUBLIC_DEFAULTS, **DEPLOYMENT_SECRET_DEFAULTS}


def _dotenv(environ, defaults):
    lines = []
    for name, default in defaults.items():
        value = environ.get(name, default)
        if value is None or (default is None and not value.strip()):
            raise ValueError(f"Set {name}")
        if any(char in value for char in "\r\n\x00"):
            raise ValueError(f"{name} must be a single-line value without NUL characters")
        # Compose interpolates double-quoted dotenv values. $$ preserves literal dollars
        # in passwords/hashes; JSON quoting handles quotes and backslashes safely.
        lines.append(name + "=" + json.dumps(value, ensure_ascii=False).replace("$", "$$"))
    return "\n".join(lines) + "\n"


def deployment_dotenv(environ):
    return _dotenv(environ, DEPLOYMENT_PUBLIC_DEFAULTS)


def deployment_secret_dotenv(environ):
    return _dotenv(environ, DEPLOYMENT_SECRET_DEFAULTS)


def bounded_int(value, minimum, maximum):
    number = int(value)
    if not minimum <= number <= maximum:
        raise ValueError(f"Value must be between {minimum} and {maximum}")
    return number


def boolean(environ, name, default):
    value = environ.get(name, default).lower()
    if value not in {"true", "false"}:
        raise ValueError(f"{name} must be true or false")
    return value == "true"


def required(environ, name):
    value = environ.get(name, "").strip()
    if not value:
        raise ValueError(f"Set {name}")
    return value


def input_file(environ, name):
    path = Path(required(environ, name)).expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"{name} must point to an existing file")
    # SSH private-key contents are deliberately not read here.
    return str(path)


def configuration(args, environ):
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", args.environment):
        raise ValueError("Environment must match [a-z][a-z0-9-]{0,31}")
    if not re.fullmatch(r"sha-[0-9a-f]{40}", args.image_tag):
        raise ValueError("Image tag must be sha- followed by a full lowercase commit SHA")
    if not re.fullmatch(r"ghcr\.io/[a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*", args.image_prefix):
        raise ValueError("Image prefix must be ghcr.io/<owner>/<repository> in lowercase")
    host = required(environ, "DEPLOY_HOST")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?", host):
            raise ValueError("DEPLOY_HOST must be an IP address or hostname") from None
    user = required(environ, "DEPLOY_USER")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", user):
        raise ValueError("DEPLOY_USER must be a Linux username")
    key = input_file(environ, "DEPLOY_SSH_KEY_FILE")
    if any(char in key for char in "\n\r\"'"):
        raise ValueError("SSH file paths cannot contain quotes or newlines")
    port = bounded_int(environ.get("DEPLOY_PORT", "22"), 1, 65535)
    try:
        frontend_host = str(ipaddress.ip_address(environ.get("DEPLOY_FRONTEND_HOST", "127.0.0.1")))
    except ValueError:
        raise ValueError("DEPLOY_FRONTEND_HOST must be an IP address without a port") from None
    release_id = args.image_tag + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    variables = {
        "release_environment": args.environment,
        "release_image_tag": args.image_tag,
        "release_image_prefix": args.image_prefix,
        "release_id": release_id,
        "release_frontend_host": frontend_host,
        "release_frontend_port": bounded_int(environ.get("DEPLOY_FRONTEND_PORT", "8080"), 1024, 65535),
        "release_become": not args.no_become,
        "release_llm_enabled": boolean(environ, "DEPLOY_LLM_ENABLED", "false"),
        "release_local_ollama": boolean(environ, "DEPLOY_LOCAL_OLLAMA", "true"),
        "release_ollama_gpu": boolean(environ, "DEPLOY_OLLAMA_GPU", "false"),
    }
    if variables["release_llm_enabled"] and len(environ.get("INTERNAL_SERVICE_TOKEN", "")) < 32:
        raise ValueError("LLM deployment requires INTERNAL_SERVICE_TOKEN of at least 32 characters")
    if bool(environ.get("REGISTRY_USERNAME")) != bool(environ.get("REGISTRY_PASSWORD")):
        raise ValueError("Set both REGISTRY_USERNAME and REGISTRY_PASSWORD, or neither for public images")
    inventory = {"all": {"hosts": {"stand": {
        "ansible_host": host,
        "ansible_user": user,
        "ansible_port": port,
        "ansible_ssh_private_key_file": key,
        "ansible_ssh_common_args": (
            '-o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes -o BatchMode=yes'
        ),
        "ansible_python_interpreter": "/usr/bin/python3",
    }}}}
    return inventory, variables


def main(argv=None, environ=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, help="GitHub Environment, e.g. staging")
    parser.add_argument("--image-tag", required=True, help="sha-<full commit SHA> from successful CI")
    parser.add_argument("--image-prefix", required=True, help="ghcr.io/<owner>/<repository>")
    parser.add_argument("--dry-run", action="store_true", help="Validate inputs and print a secret-free plan; no SSH")
    parser.add_argument("--no-become", action="store_true", help="Do not sudo; user must already own deployment directory")
    args = parser.parse_args(argv)
    environ = dict(os.environ if environ is None else environ)
    try:
        inventory, variables = configuration(args, environ)
        dotenv = deployment_dotenv(environ)
        secret_dotenv = deployment_secret_dotenv(environ)
        print(f"Deploy {args.image_prefix}/*:{args.image_tag} to {args.environment}", flush=True)
        bind_host = variables["release_frontend_host"]
        if ":" in bind_host:
            bind_host = "[" + bind_host + "]"
        print(f"Directory: /opt/repaircalc/{args.environment}; frontend: {bind_host}:{variables['release_frontend_port']}", flush=True)
        if args.dry_run:
            print("Inputs validated; no network requests or remote changes made.")
            return 0
        if sys.platform == "win32":
            raise ValueError("Run deployment from Linux, macOS or WSL (Ansible controller is not supported on Windows)")
        executable = shutil.which("ansible-playbook", path=environ.get("PATH"))
        if not executable:
            raise ValueError("Install deployment dependencies: python3 -m pip install -r docker/ansible/requirements.txt")
        # Keep generated application secrets in a private temporary file, never argv or logs.
        with tempfile.TemporaryDirectory(prefix="repaircalc-release-") as directory:
            tmp = Path(directory)
            env_file = tmp / ".env"
            env_file.write_text(dotenv, encoding="utf-8")
            env_file.chmod(0o600)
            variables["release_env_file"] = str(env_file)
            secret_file = tmp / "secret.env"
            secret_file.write_text(secret_dotenv, encoding="utf-8")
            secret_file.chmod(0o600)
            variables["release_secret_env_file"] = str(secret_file)
            (tmp / "inventory.json").write_text(json.dumps(inventory), encoding="utf-8")
            (tmp / "vars.json").write_text(json.dumps(variables), encoding="utf-8")
            for name in DEPLOYMENT_ENV_DEFAULTS:
                environ.pop(name, None)
            environ.update({
                "ANSIBLE_HOST_KEY_CHECKING": "True",
                "ANSIBLE_DISPLAY_ARGS_TO_STDOUT": "False",
                "ANSIBLE_NO_LOG": "True",  # Includes errors: never leak dotenv or registry credentials.
                "ANSIBLE_CONFIG": str(ROOT / "docker" / "ansible" / "ansible.cfg"),
            })
            result = subprocess.run([
                executable, "-i", str(tmp / "inventory.json"),
                str(ROOT / "docker" / "ansible" / "deploy.yml"), "--extra-vars", "@" + str(tmp / "vars.json"),
            ], cwd=ROOT, env=environ, check=False)
            return result.returncode
    except (ValueError, OSError) as error:
        print(f"Release error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
