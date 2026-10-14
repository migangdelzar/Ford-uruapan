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
            fake_swift = fake_bin / "swift"
            fake_swift.write_text(
                "#!/usr/bin/env bash\n"
                "script=$1; operation=$2; service=$3; shift 3\n"
                "[[ $service == 'com.ford.test' ]] || exit 43\n"
                "[[ $operation == 'get' && -f $script ]] || exit 44\n"
                "printf '%s' '{\"OPENROUTER_API_KEY\":\"synthetic-test-value\",\"FORD_DEEPSEEK_API_KEY\":\"synthetic-test-value\",\"OTEL_EXPORTER_OTLP_API_TOKEN\":\"synthetic-test-value\"}'\n",
                encoding="utf-8",
            )
            fake_swift.chmod(0o700)
            inherited_env = {
                key: value
                for key, value in os.environ.items()
                if key not in {
                    "OPENROUTER_API_KEY",
                    "FORD_DEEPSEEK_API_KEY",
                    "OTEL_EXPORTER_OTLP_API_TOKEN",
                }
            }
            result = subprocess.run(
                [
                    str(WRAPPER),
                    "python3",
                    "-c",
                    "import os; print('loaded' if all(os.environ.get(name) for name in ('OPENROUTER_API_KEY', 'FORD_DEEPSEEK_API_KEY', 'OTEL_EXPORTER_OTLP_API_TOKEN')) else 'missing')",
                ],
                env={
                    **inherited_env,
                    "FORD_KEYCHAIN_SERVICE": "com.ford.test",
                    "PATH": f"{fake_bin}:{os.environ['PATH']}"
                },
                capture_output=True,
                text=True,
                check=True,
            )

            self.assertEqual(result.stdout.strip(), "loaded")
            self.assertNotIn("synthetic-test-value", result.stdout)
            self.assertNotIn("synthetic-test-value", result.stderr)


if __name__ == "__main__":
    unittest.main()
