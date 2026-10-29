import base64
import json
import os
import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "oci" / "create_secret_request.py"
TEST_ENV = {
    **os.environ,
    "OCI_COMPARTMENT_OCID": "ocid1.compartment.oc1.iad.exampleCompartment",
    "OCI_VAULT_ID": "ocid1.vault.oc1.iad.exampleVault",
    "OCI_VAULT_KEY_ID": "ocid1.key.oc1.iad.exampleKey",
}


class CreateSecretRequestTest(unittest.TestCase):
    def test_encodes_stdin_secret_and_emits_only_oci_request(self):
        sample_value = b"synthetic-test-value"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "create", "ford-prod-example-api-key"],
            input=sample_value,
            capture_output=True,
            check=True,
            env=TEST_ENV,
        )

        request = json.loads(result.stdout)
        self.assertEqual(
            request,
            {
                "compartmentId": TEST_ENV["OCI_COMPARTMENT_OCID"],
                "keyId": TEST_ENV["OCI_VAULT_KEY_ID"],
                "secretContentContent": base64.b64encode(sample_value).decode("ascii"),
                "secretContentName": "current",
                "secretContentStage": "CURRENT",
                "secretName": "ford-prod-example-api-key",
                "vaultId": TEST_ENV["OCI_VAULT_ID"],
            },
        )
        self.assertEqual(result.stderr, b"")

    def test_encodes_test_secret_using_a_separate_test_name(self):
        sample_value = b"synthetic-current-value"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "create-test", "ford-test-example-api-key"],
            input=sample_value,
            capture_output=True,
            check=True,
            env=TEST_ENV,
        )

        request = json.loads(result.stdout)
        self.assertEqual(request["secretName"], "ford-test-example-api-key")
        self.assertEqual(
            base64.b64decode(request["secretContentContent"]), sample_value
        )

    def test_accepts_a_root_tenancy_ocid_for_test_secret_creation(self):
        env = {
            **TEST_ENV,
            "OCI_COMPARTMENT_OCID": "ocid1.tenancy.oc1..syntheticTenancy",
            "OCI_VAULT_ID": "ocid1.vault.oc1.iad.syntheticVault.part",
            "OCI_VAULT_KEY_ID": "ocid1.key.oc1.iad.syntheticKey.part",
        }
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "create-test", "ford-test-api-key"],
            input=b"synthetic-current-value",
            capture_output=True,
            check=True,
            env=env,
        )

        request = json.loads(result.stdout)
        self.assertEqual(request["compartmentId"], env["OCI_COMPARTMENT_OCID"])

    def test_rejects_empty_secret_without_emitting_request(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "create", "ford-prod-example-api-key"],
            input=b"",
            capture_output=True,
            env=TEST_ENV,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")
        self.assertNotIn(b"synthetic", result.stderr)

    def test_rejects_invalid_secret_name(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "create", "not-a-vault-secret"],
            input=b"synthetic-test-value",
            capture_output=True,
            env=TEST_ENV,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_rotation_request_contains_only_secret_version_content(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "rotate", "ford-prod-example-api-key"],
            input=b"synthetic-test-value",
            capture_output=True,
            check=True,
            env={},
        )

        request = json.loads(result.stdout)
        self.assertEqual(
            set(request),
            {"secretContentContent", "secretContentName", "secretContentStage"},
        )

    def test_rotation_requests_use_unique_version_names(self):
        names = []
        for _ in range(2):
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "rotate", "ford-test-example-api-key"],
                input=b"synthetic-test-value",
                capture_output=True,
                check=True,
                env={},
            )
            names.append(json.loads(result.stdout)["secretContentName"])

        self.assertRegex(names[0], r"^rotation-[0-9a-f]{32}$")
        self.assertNotEqual(names[0], names[1])


if __name__ == "__main__":
    unittest.main()
