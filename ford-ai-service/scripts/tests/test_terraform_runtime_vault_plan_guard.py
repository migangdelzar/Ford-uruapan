import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[3]
GUARD = REPO_ROOT / "scripts" / "terraform_runtime_vault_plan_guard.py"
CONTRACT = json.loads((REPO_ROOT / "deployment/terraform/runtime-vault-policy.json").read_text(encoding="utf-8"))
VAULT_OCID = "ocid1.vault.synthetic"
COMPARTMENT_OCID = "ocid1.tenancy.synthetic"


def expected_policy_statements(vault_ocid=VAULT_OCID, compartment_ocid=COMPARTMENT_OCID):
    return sorted(
        f"Allow dynamic-group {group_name} to read secret-bundles in compartment id {compartment_ocid} "
        f"where all {{target.vault.id = '{vault_ocid}', target.secret.name = '{secret_name}'}}"
        for identity, group_name in CONTRACT["instances"].items()
        for secret_name in CONTRACT["secret_names"][identity]
    )


def plan_with_group_change(after_description="runtime principal"):
    group = {
        "address": 'oci_identity_dynamic_group.runtime_vault["app"]',
        "change": {
            "actions": ["update"],
            "before": {
                "compartment_id": "ocid1.tenancy.synthetic",
                "description": "runtime principal",
                "name": "ford-runtime-app-emme1",
            },
            "after": {
                "compartment_id": "ocid1.tenancy.synthetic",
                "description": after_description,
                "name": "ford-runtime-app-emme1",
            },
            "before_sensitive": {"compartment_id": False},
            "after_sensitive": {"compartment_id": True},
            "after_unknown": {},
        },
    }
    policy = {
        "address": "oci_identity_policy.runtime_vault[0]",
        "change": {
            "actions": ["no-op"],
            "before": {"statements": expected_policy_statements()},
            "after": {"statements": expected_policy_statements()},
            "after_unknown": {"statements": False},
        },
    }
    return {"resource_changes": [group, policy]}


class TerraformRuntimeVaultPlanGuardTest(unittest.TestCase):
    def test_catalog_reader_secret_is_granted_only_to_app(self):
        secret_name = "ford-prod-catalog-reader-password"

        self.assertIn(secret_name, CONTRACT["secret_names"]["app"])
        self.assertNotIn(secret_name, CONTRACT["secret_names"]["data"])

    def run_guard(self, plan, vault_ocid=VAULT_OCID, compartment_ocid=COMPARTMENT_OCID):
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            return subprocess.run(
                [
                    sys.executable,
                    str(GUARD),
                    str(plan_path),
                    "--vault-ocid",
                    vault_ocid,
                    "--compartment-ocid",
                    compartment_ocid,
                ],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_accepts_group_update_when_values_are_unchanged_and_only_sensitivity_changes(self):
        result = self.run_guard(plan_with_group_change())

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Plan scope passed", result.stdout)

    def test_rejects_group_update_when_an_attribute_value_changes(self):
        result = self.run_guard(plan_with_group_change("changed principal description"))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("oci_identity_dynamic_group.runtime_vault", result.stderr)

    def test_rejects_group_update_when_an_after_value_is_unknown(self):
        plan = plan_with_group_change()
        plan["resource_changes"][0]["change"]["after_unknown"] = {"matching_rule": True}

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("oci_identity_dynamic_group.runtime_vault", result.stderr)

    def test_rejects_in_place_policy_broadening(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["actions"] = ["update"]
        policy["after"]["statements"].append(
            "Allow dynamic-group ford-runtime-app-emme1 to manage all-resources in tenancy"
        )

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_missing_active_secret_grant(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["actions"] = ["update"]
        policy["after"]["statements"].pop()

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_policy_statement_scoped_to_another_vault(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["actions"] = ["update"]
        policy["after"]["statements"] = expected_policy_statements(vault_ocid="ocid1.vault.other")

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_policy_statement_scoped_to_another_compartment(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["actions"] = ["update"]
        policy["after"]["statements"] = expected_policy_statements(compartment_ocid="ocid1.compartment.other")

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_unrelated_resource_changes(self):
        plan = plan_with_group_change()
        plan["resource_changes"].append(
            {
                "address": "oci_core_instance.unrelated",
                "change": {"actions": ["update"]},
            }
        )

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_policy_attachment_or_metadata_changes(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["actions"] = ["update"]
        policy["before"].update({"name": "ford-runtime-secret-read", "compartment_id": "ocid1.tenancy.synthetic"})
        policy["after"].update({"name": "ford-runtime-secret-read-renamed", "compartment_id": "ocid1.tenancy.synthetic"})

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_rejects_unknown_policy_statements(self):
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["after_unknown"]["statements"] = True

        result = self.run_guard(plan)

        self.assertNotEqual(result.returncode, 0)

    def test_accepts_valid_ocids_with_underscores_and_hyphens(self):
        vault_ocid = "ocid1.vault.oc1.iad.a_m-a"
        compartment_ocid = "ocid1.compartment.oc1.iad.a_m-a"
        plan = plan_with_group_change()
        policy = plan["resource_changes"][1]["change"]
        policy["before"]["statements"] = expected_policy_statements(vault_ocid, compartment_ocid)
        policy["after"]["statements"] = expected_policy_statements(vault_ocid, compartment_ocid)

        result = self.run_guard(plan, vault_ocid, compartment_ocid)

        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
