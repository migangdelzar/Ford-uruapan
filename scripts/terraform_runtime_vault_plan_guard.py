#!/usr/bin/env python3
"""Reject Terraform plans outside the exact runtime Vault read policy."""

import argparse
import json
import re
import sys
from pathlib import Path

ALLOWED = {
    'oci_identity_dynamic_group.runtime_vault["app"]',
    'oci_identity_dynamic_group.runtime_vault["data"]',
    'oci_identity_policy.runtime_vault[0]',
}
POLICY_ADDRESS = 'oci_identity_policy.runtime_vault[0]'
CONTRACT_PATH = Path(__file__).resolve().parents[1] / "deployment/terraform/runtime-vault-policy.json"


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


def load_expected_statements(vault_ocid: str, compartment_ocid: str) -> set[str]:
    if not re.fullmatch(r"ocid1\.vault\.[A-Za-z0-9._-]+", vault_ocid):
        raise ValueError("Expected Vault OCID is missing or malformed.")
    if not re.fullmatch(r"ocid1\.(?:compartment|tenancy)\.[A-Za-z0-9._-]+", compartment_ocid):
        raise ValueError("Expected compartment OCID is missing or malformed.")

    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    instances = contract.get("instances")
    secrets = contract.get("secret_names")
    required_keys = {"app", "data"}
    if not isinstance(instances, dict) or set(instances) != required_keys:
        raise ValueError("Runtime Vault contract must define exactly the app and data identities.")
    if not isinstance(secrets, dict) or set(secrets) != required_keys:
        raise ValueError("Runtime Vault contract must define secret names for exactly the app and data identities.")

    statements = set()
    for identity in sorted(required_keys):
        group_name = instances[identity]
        names = secrets[identity]
        if not isinstance(group_name, str) or not re.fullmatch(r"ford-runtime-[a-z0-9-]+", group_name):
            raise ValueError(f"Invalid runtime identity name in the {identity} contract.")
        if not isinstance(names, list) or not names:
            raise ValueError(f"The {identity} identity must have at least one named secret.")
        if any(not isinstance(name, str) or not re.fullmatch(r"ford-prod-[a-z0-9-]+", name) for name in names):
            raise ValueError(f"Invalid secret name in the {identity} contract.")
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate secret name in the {identity} contract.")
        statements.update(
            f"Allow dynamic-group {group_name} to read secret-bundles in compartment id {compartment_ocid} "
            f"where all {{target.vault.id = '{vault_ocid}', target.secret.name = '{name}'}}"
            for name in names
        )
    return statements


def validate_policy_change(item: dict, expected_statements: set[str]) -> list[str]:
    change = item.get("change", {})
    actions = change.get("actions", [])
    if actions not in (["no-op"], ["update"]):
        return ["runtime Vault policy must be a no-op or an in-place update"]

    after = change.get("after")
    before = change.get("before")
    after_unknown = change.get("after_unknown", {})
    if not isinstance(before, dict) or not isinstance(after, dict):
        return ["runtime Vault policy before/after values must be present"]
    if contains_unknown(after_unknown):
        return ["planned runtime Vault policy statements must be fully known"]
    actual = after.get("statements")
    if not isinstance(actual, list) or any(not isinstance(statement, str) for statement in actual):
        return ["planned runtime Vault policy statements are missing or malformed"]
    if len(actual) != len(set(actual)) or set(actual) != expected_statements:
        return ["planned runtime Vault policy does not exactly match the named app/data secret contract"]
    stable_before = {key: value for key, value in before.items() if key != "statements"}
    stable_after = {key: value for key, value in after.items() if key != "statements"}
    if stable_before != stable_after:
        return ["runtime Vault policy metadata and attachment must remain unchanged"]
    if actions == ["no-op"] and before.get("statements") != actual:
        return ["no-op runtime Vault policy statements must match the existing policy"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan_json", type=Path)
    parser.add_argument("--vault-ocid", required=True)
    parser.add_argument("--compartment-ocid", required=True)
    args = parser.parse_args()

    try:
        expected_statements = load_expected_statements(args.vault_ocid, args.compartment_ocid)
        plan = json.loads(args.plan_json.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"Rejected Terraform plan: {error}", file=sys.stderr)
        return 1

    changes = plan.get("resource_changes", [])
    unexpected = []
    for item in changes:
        actions = item.get("change", {}).get("actions", [])
        address = item.get("address")
        if address not in ALLOWED:
            unexpected.append((item.get("address", "<unknown>"), actions))
        elif address == POLICY_ADDRESS:
            errors = validate_policy_change(item, expected_statements)
            unexpected.extend((address, [message]) for message in errors)
        elif actions != ["no-op"]:
            if not is_sensitivity_only_group_update(item.get("change", {})):
                unexpected.append((address, actions))
    if unexpected:
        for address, actions in unexpected:
            print(f"Rejected Terraform plan change: {address} {actions}", file=sys.stderr)
        return 1

    policy = next((item for item in changes if item.get("address") == POLICY_ADDRESS), None)
    if policy is None:
        print("Rejected plan: managed runtime Vault policy is absent.", file=sys.stderr)
        return 1
    print("Plan scope passed: only the exact named app/data Vault read policy can change.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
