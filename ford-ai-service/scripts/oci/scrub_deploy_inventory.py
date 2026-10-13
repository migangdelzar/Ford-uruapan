#!/usr/bin/env python3
"""Remove known secret fields from a generated Ansible inventory in place."""

from __future__ import annotations

import os
import stat
import sys
import tempfile
from pathlib import Path

import yaml


SECRET_FIELDS = {
    "postgres_password",
    "redis_password",
    "ford_operator_password",
    "ford_meta_app_secret",
    "ford_meta_verify_token",
    "ford_meta_whatsapp_access_token",
    "ford_ai_ollama_access_client_secret",
    "ford_mcp_access_client_secret",
    "openai_api_key",
    "groq_api_key",
    "gemini_api_key",
    "cloudflare_api_token",
    "openrouter_api_key",
    "typesafe_api_key",
    "ghcr_token",
    "grafana_cloud_api_token",
    "langfuse_secret_key",
    "mcp_http_bearer_token",
}


def scrub(node: object) -> int:
    removed = 0
    if isinstance(node, dict):
        for key in list(node):
            if isinstance(key, str) and key.lower() in SECRET_FIELDS:
                del node[key]
                removed += 1
            else:
                removed += scrub(node[key])
    elif isinstance(node, list):
        for item in node:
            removed += scrub(item)
    return removed


def main() -> int:
    if len(sys.argv) != 2:
        print("Expected a generated Ansible inventory path.", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file() or path.is_symlink():
        print("Inventory must be an existing regular file, not a symlink.", file=sys.stderr)
        return 2

    try:
        inventory = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(inventory, dict):
            print("Inventory structure is invalid; no change was made.", file=sys.stderr)
            return 2
    except (OSError, UnicodeError, yaml.YAMLError):
        print("Inventory could not be parsed; no change was made.", file=sys.stderr)
        return 2

    removed = scrub(inventory)
    if removed == 0:
        print("No known secret fields were present; inventory was unchanged.")
        return 0

    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.", delete=False
        ) as temp_file:
            temp_name = temp_file.name
            os.fchmod(temp_file.fileno(), stat.S_IMODE(path.stat().st_mode))
            yaml.safe_dump(inventory, temp_file, sort_keys=False)
            temp_file.flush()
            os.fsync(temp_file.fileno())
        os.replace(temp_name, path)
        os.chmod(path, 0o600)
    except OSError:
        if temp_name:
            try:
                os.unlink(temp_name)
            except OSError:
                pass
        print("Inventory could not be rewritten; the original was preserved.", file=sys.stderr)
        return 1

    print(f"Removed {removed} secret fields from the generated inventory.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
