#!/usr/bin/env python3
"""Reject Terraform plans outside the protected runtime Vault policy scope."""

import json
import sys
from pathlib import Path

ALLOWED = {
    'oci_identity_dynamic_group.runtime_vault["app"]',
    'oci_identity_dynamic_group.runtime_vault["data"]',
    'oci_identity_policy.runtime_vault[0]',
}


def main() -> int:
    plan = json.loads(Path(sys.argv[1]).read_text())
    changes = plan.get("resource_changes", [])
    unexpected = []
    for item in changes:
        actions = item.get("change", {}).get("actions", [])
        address = item.get("address")
        allowed_actions = [["no-op"], ["update"]] if address == 'oci_identity_policy.runtime_vault[0]' else [["no-op"]]
        if address not in ALLOWED or actions not in allowed_actions:
            unexpected.append((item.get("address", "<unknown>"), actions))
    if unexpected:
        for address, actions in unexpected:
            print(f"Rejected Terraform plan change: {address} {actions}", file=sys.stderr)
        return 1
    policy = next((item for item in changes if item.get("address") == 'oci_identity_policy.runtime_vault[0]'), None)
    if policy is None:
        print("Rejected plan: managed runtime Vault policy is absent.", file=sys.stderr)
        return 1
    if policy.get("change", {}).get("actions") not in (["no-op"], ["update"]):
        print("Rejected plan: expected no change or an in-place runtime Vault policy update.", file=sys.stderr)
        return 1
    print("Plan scope passed: only the existing runtime Vault policy can change.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
