import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "oci" / "scrub_deploy_inventory.py"


class ScrubDeployInventoryTest(unittest.TestCase):
    def test_removes_secret_fields_preserves_other_settings_and_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.yml"
            path.write_text(
                "all:\n"
                "  vars:\n"
                "    ansible_user: opc\n"
                "    postgres_password: synthetic-postgres-value\n"
                "    openrouter_api_key: synthetic-provider-value\n"
                "  children:\n"
                "    app:\n"
                "      hosts:\n"
                "        emme-1:\n"
                "          ansible_host: 10.0.0.1\n",
                encoding="utf-8",
            )
            path.chmod(0o600)

            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(path)],
                capture_output=True,
                text=True,
                check=True,
            )

            sanitized = path.read_text(encoding="utf-8")
            self.assertIn("ansible_user: opc", sanitized)
            self.assertIn("ansible_host: 10.0.0.1", sanitized)
            self.assertNotIn("password", sanitized)
            self.assertNotIn("api_key", sanitized)
            self.assertNotIn("synthetic", sanitized)
            self.assertNotIn("synthetic", result.stdout + result.stderr)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertIn("Removed 2 secret fields", result.stdout)

    def test_invalid_inventory_is_preserved_without_printing_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.yml"
            original = "not: [valid yaml"
            path.write_text(original, encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(path)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(path.read_text(encoding="utf-8"), original)
            self.assertNotIn(original, result.stderr)


if __name__ == "__main__":
    unittest.main()
