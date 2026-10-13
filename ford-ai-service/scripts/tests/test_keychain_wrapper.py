import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
WRAPPER = REPO_ROOT / "scripts" / "with-keychain-secrets.sh"


class KeychainWrapperTest(unittest.TestCase):
    def test_loads_keychain_values_only_into_child_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            fake_bin = Path(directory)
            fake_security = fake_bin / "security"
            fake_security.write_text(
                "#!/usr/bin/env bash\n"
                "while (( $# )); do\n"
                "  if [[ $1 == '-a' ]]; then account=$2; shift 2; else shift; fi\n"
                "done\n"
                "if [[ $account == 'OPENROUTER_API_KEY' ]]; then printf '%s' 'synthetic-test-value'; exit 0; fi\n"
                "exit 44\n",
                encoding="utf-8",
            )
            fake_security.chmod(0o700)
            result = subprocess.run(
                [
                    str(WRAPPER),
                    "python3",
                    "-c",
                    "import os; print('loaded' if os.environ.get('OPENROUTER_API_KEY') else 'missing')",
                ],
                env={**os.environ, "PATH": f"{fake_bin}:{os.environ['PATH']}"},
                capture_output=True,
                text=True,
                check=True,
            )

            self.assertEqual(result.stdout.strip(), "loaded")
            self.assertNotIn("synthetic-test-value", result.stdout)
            self.assertNotIn("synthetic-test-value", result.stderr)


if __name__ == "__main__":
    unittest.main()
