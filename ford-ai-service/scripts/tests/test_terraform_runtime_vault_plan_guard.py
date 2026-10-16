import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[3]
GUARD = REPO_ROOT / "scripts" / "terraform_runtime_vault_plan_guard.py"


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
        "change": {"actions": ["no-op"]},
    }
    return {"resource_changes": [group, policy]}


class TerraformRuntimeVaultPlanGuardTest(unittest.TestCase):
    def run_guard(self, plan):
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(GUARD), str(plan_path)],
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


if __name__ == "__main__":
    unittest.main()
