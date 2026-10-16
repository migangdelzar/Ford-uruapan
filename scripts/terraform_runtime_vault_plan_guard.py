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


def contains_unknown(value) -> bool:
    if isinstance(value, dict):
        return any(contains_unknown(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_unknown(item) for item in value)
    return value is True


def is_sensitivity_only_group_update(change: dict) -> bool:
    before = change.get("before")
    after = change.get("after")
    return (
        change.get("actions") == ["update"]
        and before is not None
        and before == after
        and change.get("before_sensitive") != change.get("after_sensitive")
        and not contains_unknown(change.get("after_unknown", {}))
    )


def main() -> int:
    plan = json.loads(Path(sys.argv[1]).read_text())
    changes = plan.get("resource_changes", [])
    unexpected = []
    for item in changes:
        actions = item.get("change", {}).get("actions", [])
        address = item.get("address")
        if address not in ALLOWED:
            unexpected.append((item.get("address", "<unknown>"), actions))
        elif address == 'oci_identity_policy.runtime_vault[0]' and actions not in (["no-op"], ["update"]):
            unexpected.append((address, actions))
        elif address != 'oci_identity_policy.runtime_vault[0]' and actions != ["no-op"]:
            if not is_sensitivity_only_group_update(item.get("change", {})):
                unexpected.append((address, actions))
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
