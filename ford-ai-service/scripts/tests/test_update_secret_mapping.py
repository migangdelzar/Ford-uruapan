import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "oci" / "update_secret_mapping.py"


class UpdateSecretMappingTest(unittest.TestCase):
    def test_adds_and_replaces_only_the_requested_ocid_mapping(self):
        first_ocid = "ocid1.vaultsecret.oc1.iad.synthetic.First"
        second_ocid = "ocid1.vaultsecret.oc1.iad.synthetic.Second"
        other_ocid = "ocid1.vaultsecret.oc1.iad.syntheticOther"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vault-secret-ocids.env"
            path.write_text(f"FORD_VAULT_SECRET_OCID_OTHER={other_ocid}\n", encoding="utf-8")

            for ocid in (first_ocid, second_ocid):
                subprocess.run(
                    [sys.executable, str(SCRIPT), str(path), "FORD_VAULT_SECRET_OCID_SAMPLE", ocid],
                    check=True,
                    capture_output=True,
                )

            self.assertEqual(
                path.read_text(encoding="utf-8"),
                f"FORD_VAULT_SECRET_OCID_OTHER={other_ocid}\n"
                f"FORD_VAULT_SECRET_OCID_SAMPLE={second_ocid}\n",
            )
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)

    def test_refuses_invalid_existing_entry_without_overwriting_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vault-secret-ocids.env"
            original = "THIS_IS_NOT_A_MAPPING=do-not-change\n"
            path.write_text(original, encoding="utf-8")

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    str(path),
                    "FORD_VAULT_SECRET_OCID_SAMPLE",
                    "ocid1.vaultsecret.oc1.iad.syntheticSecret",
                ],
                capture_output=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(path.read_text(encoding="utf-8"), original)
            self.assertNotIn(b"do-not-change", result.stderr)


if __name__ == "__main__":
    unittest.main()
