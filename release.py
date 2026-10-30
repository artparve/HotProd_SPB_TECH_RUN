#!/usr/bin/env python3
"""Compatibility entry point; deployment implementation lives in scripts/release.py."""

from scripts.release import main


if __name__ == "__main__":
    raise SystemExit(main())
