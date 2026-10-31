#!/usr/bin/env python3
"""Print an environment-ready PBKDF2 hash; read passwords only from a terminal."""
import base64
import getpass
import hashlib
import secrets
import sys


def main() -> int:
    if not sys.stdin.isatty():
        print("Запустите скрипт в интерактивном терминале.", file=sys.stderr)
        return 2
    password = getpass.getpass("Пароль администратора: ")
    if len(password) < 12 or len(password) > 1024:
        print("Нужно от 12 до 1024 символов.", file=sys.stderr)
        return 1
    if getpass.getpass("Повторите пароль: ") != password:
        print("Пароли не совпали.", file=sys.stderr)
        return 1
    salt = secrets.token_bytes(24)
    derived = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600_000)
    encoded = "pbkdf2-sha256$600000$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(derived).decode()
    # Single quotes keep dollar signs literal in Compose's .env reader and in bash.
    print("ADMIN_PASSWORD_HASH='" + encoded + "'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
