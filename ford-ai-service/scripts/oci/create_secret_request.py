#!/usr/bin/env python3
"""Build an OCI Vault secret request from a value received on stdin."""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import uuid


SECRET_NAME = re.compile(r"ford-(?:prod|test)-[a-z0-9]+(?:-[a-z0-9]+)*\Z")
OCID = re.compile(
    r"ocid1\.(?:compartment|tenancy|vault|key)\.oc1\.[a-z0-9-]*\."
    r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*\Z"
)


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in {"create", "create-test", "rotate"}:
        print("Expected operation (create, create-test, or rotate) and a Ford secret name.", file=sys.stderr)
        return 2
    operation, secret_name = sys.argv[1:]
    if not SECRET_NAME.fullmatch(secret_name):
        print("A valid ford-prod-* secret name is required.", file=sys.stderr)
        return 2

    if operation in {"create", "create-test"}:
        try:
            compartment_id = os.environ["OCI_COMPARTMENT_OCID"]
            vault_id = os.environ["OCI_VAULT_ID"]
            key_id = os.environ["OCI_VAULT_KEY_ID"]
        except KeyError:
            print("OCI compartment, vault, and key OCIDs must be configured.", file=sys.stderr)
            return 2

        if not all(OCID.fullmatch(value) for value in (compartment_id, vault_id, key_id)):
            print("OCI compartment, vault, and key values must be valid OCIDs.", file=sys.stderr)
            return 2

    secret_value = sys.stdin.buffer.read()
    if not secret_value:
        print("Secret value is empty.", file=sys.stderr)
        return 2

    content = {
        "secretContentContent": base64.b64encode(secret_value).decode("ascii"),
        "secretContentName": (
            f"rotation-{uuid.uuid4().hex}" if operation == "rotate" else "current"
        ),
        "secretContentStage": "CURRENT",
    }
    request = (
        {
            "compartmentId": compartment_id,
            "keyId": key_id,
            **content,
            "secretName": secret_name,
            "vaultId": vault_id,
        }
        if operation in {"create", "create-test"}
        else content
    )
    json.dump(request, sys.stdout, separators=(",", ":"))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
