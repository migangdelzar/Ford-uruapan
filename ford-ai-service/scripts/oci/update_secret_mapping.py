#!/usr/bin/env python3
"""Atomically maintain the local, non-secret OCI Vault OCID mapping file."""

from __future__ import annotations

import os
import re
import sys
import tempfile
from pathlib import Path


MAPPING_NAME = re.compile(r"FORD_VAULT_SECRET_OCID_[A-Z][A-Z0-9_]*\Z")
SECRET_OCID = re.compile(
    r"ocid1\.vaultsecret\.oc1\.[a-z0-9-]*\.[A-Za-z0-9_-]+"
    r"(?:\.[A-Za-z0-9_-]+)*\Z"
)


def main() -> int:
    if len(sys.argv) != 4:
        print("Expected mapping path, environment mapping name, and secret OCID.", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    mapping_name = sys.argv[2]
    secret_ocid = sys.argv[3]
    if not MAPPING_NAME.fullmatch(mapping_name) or not SECRET_OCID.fullmatch(secret_ocid):
        print("Mapping name or OCI secret OCID is invalid.", file=sys.stderr)
        return 2
    if path.is_symlink():
        print("The Vault mapping file must not be a symlink.", file=sys.stderr)
        return 2

    existing: list[str] = []
    if path.exists():
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line or line.startswith("#"):
                    existing.append(line)
                    continue
                name, separator, value = line.partition("=")
                if (
                    not separator
                    or not MAPPING_NAME.fullmatch(name)
                    or not SECRET_OCID.fullmatch(value)
                ):
                    print("Vault mapping file contains an invalid entry; no change was made.", file=sys.stderr)
                    return 2
                if name != mapping_name:
                    existing.append(line)
        except (OSError, UnicodeError):
            print("Vault mapping file could not be read; no change was made.", file=sys.stderr)
            return 2

    existing.append(f"{mapping_name}={secret_ocid}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.", delete=False
        ) as temp_file:
            temp_name = temp_file.name
            os.fchmod(temp_file.fileno(), 0o600)
            temp_file.write("\n".join(existing) + "\n")
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
        print("Vault mapping file could not be updated.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
