import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
REMOTE_LIBRARY = REPO_ROOT / ".mise" / "lib" / "remote.sh"


class LoadVaultMappingsTest(unittest.TestCase):
    def run_loader(self, mapping_text: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            project = root / "ford-ai-service"
            project.mkdir(parents=True)
            mapping_file = root / "deployment" / "config" / "vault-secret-ocids.env"
            mapping_file.parent.mkdir(parents=True)
            mapping_file.write_text(mapping_text, encoding="utf-8")
            env = {**os.environ, "MISE_PROJECT_ROOT": str(project)}
            script = (
                f"source {str(REMOTE_LIBRARY)!r}; "
                "load_vault_secret_mappings; "
                "[[ -n ${FORD_VAULT_SECRET_OCID_SAMPLE:-} ]] && printf 'present\\n'"
            )
            return subprocess.run(
                ["bash", "-c", script],
                env=env,
                capture_output=True,
                text=True,
            )

    def test_loads_valid_secret_ocid_without_printing_it(self):
        resource_id = ".".join(("ocid1", "vaultsecret", "oc1", "iad", "synthetic", "Secret"))
        result = self.run_loader(f"FORD_VAULT_SECRET_OCID_SAMPLE={resource_id}\n")

        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "present\n")
        self.assertNotIn(resource_id, result.stdout + result.stderr)

    def test_rejects_invalid_mapping_without_interpreting_or_echoing_it(self):
        malicious_line = "FORD_VAULT_SECRET_OCID_SAMPLE=$(touch /tmp/should-not-exist)"
        result = self.run_loader(malicious_line + "\n")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotIn("touch", result.stderr)


if __name__ == "__main__":
    unittest.main()
