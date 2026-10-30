"""Small, testable entry points used by declarative GitHub Actions workflows."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from shutil import copyfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from . import release

ROOT = Path(__file__).resolve().parents[1]


def repository(environ):
    value = release.required(environ, "REPOSITORY")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*/[a-zA-Z0-9][a-zA-Z0-9_.-]*", value):
        raise ValueError("REPOSITORY must be owner/repository")
    return value


def commit_sha(environ):
    value = release.required(environ, "RELEASE_SHA")
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("RELEASE_SHA must be a full lowercase commit SHA")
    return value


def check_release(environ):
    sha = commit_sha(environ)
    repo = repository(environ)
    token = release.required(environ, "GH_TOKEN")
    url = (f"https://api.github.com/repos/{repo}/actions/workflows/ci.yml/runs"
           f"?head_sha={sha}&event=push&branch=master&status=success&per_page=100")
    request = Request(url, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urlopen(request, timeout=30) as response:
        runs = json.load(response)["workflow_runs"]
    if not any(run.get("head_sha") == sha and run.get("conclusion") == "success"
               and run.get("head_branch") == "master" and run.get("event") == "push"
               for run in runs):
        raise ValueError("No successful master CI run found for this SHA; wait for all images to be published")
    print("Successful master CI release verified.")
    return 0


def deploy(environ):
    sha = commit_sha(environ)
    prefix = "ghcr.io/" + repository(environ).lower()
    environment = release.required(environ, "DEPLOY_ENVIRONMENT")
    env = dict(environ)
    key = env.pop("SSH_PRIVATE_KEY", "")
    if not key.strip():
        raise ValueError("Set the Environment secret DEPLOY_SSH_PRIVATE_KEY")
    # The private directory is removed on success and on any release error.
    with tempfile.TemporaryDirectory(prefix="repaircalc-", dir=environ.get("RUNNER_TEMP")) as directory:
        path = Path(directory, "id_deploy")
        path.write_text(key.replace("\r\n", "\n").rstrip("\r\n") + "\n", encoding="utf-8")
        path.chmod(0o600)
        env["DEPLOY_SSH_KEY_FILE"] = str(path)
        return release.main([
            "--environment", environment, "--image-tag", "sha-" + sha, "--image-prefix", prefix,
        ], environ=env)


def image_prefix(environ):
    prefix = "ghcr.io/" + repository(environ).lower()
    output = Path(release.required(environ, "GITHUB_OUTPUT"))
    with output.open("a", encoding="utf-8") as file:
        file.write("prefix=" + prefix + "\n")
    return 0


def validate_compose(environ):
    # This CI-only file contains a synthetic password, never deployment credentials.
    with tempfile.TemporaryDirectory(prefix="repaircalc-compose-", dir=environ.get("RUNNER_TEMP")) as directory:
        public_path = Path(directory, ".env")
        copyfile(ROOT / "env/.env.example", public_path)
        path = Path(directory, "secret.env")
        path.write_text("DB_PASSWORD=ci-validation-only\n", encoding="utf-8")
        env = {name: value for name, value in environ.items() if name not in release.DEPLOYMENT_ENV_DEFAULTS}
        env.update(IMAGE_PREFIX="ghcr.io/repaircalc/ci", IMAGE_TAG="sha-" + "0" * 40, COMPOSE_PROFILES="")
        return subprocess.run([
            "docker", "compose", "--env-file", str(public_path), "--env-file", str(path),
            "-f", "docker/docker-compose.yml", "-f", "docker/docker-compose.release.yml",
            "config", "--quiet",
        ], cwd=ROOT, env=env, check=False).returncode


COMMANDS = {
    "check-release": check_release,
    "deploy": deploy,
    "image-prefix": image_prefix,
    "validate-compose": validate_compose,
}


def main(argv=None, environ=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=COMMANDS)
    args = parser.parse_args(argv)
    try:
        return COMMANDS[args.command](dict(os.environ if environ is None else environ))
    except HTTPError as error:
        print(f"GitHub API request failed (HTTP {error.code})", file=sys.stderr)
    except URLError:
        print("GitHub API is unavailable", file=sys.stderr)
    except (ValueError, OSError, KeyError, TypeError):
        # Do not include remote responses, secret values or request headers in logs.
        print(f"Automation command {args.command} failed: check its inputs and required settings", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
