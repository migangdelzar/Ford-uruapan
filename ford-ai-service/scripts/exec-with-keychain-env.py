#!/usr/bin/env python3
"""Exec a child with Keychain values supplied as JSON on stdin."""

import json
import os
import re
import sys


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: exec-with-keychain-env.py COMMAND [ARG ...]")

    try:
        values = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise SystemExit("Could not read Keychain values.") from None

    if not isinstance(values, dict):
        raise SystemExit("Keychain response must be a JSON object.")

    env = os.environ.copy()
    for name, value in values.items():
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise SystemExit("Keychain response contains an invalid environment name.")
        if not isinstance(value, str):
            raise SystemExit("Keychain response contains an invalid value.")
        env[name] = value

    os.execvpe(sys.argv[1], sys.argv[1:], env)


if __name__ == "__main__":
    main()
