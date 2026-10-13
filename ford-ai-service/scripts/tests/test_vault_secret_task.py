import base64
import json
import os
import pty
import select
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
TASK = REPO_ROOT / ".mise" / "tasks" / "deploy" / "vault-secret"
REMOTE_LIBRARY = REPO_ROOT / ".mise" / "lib" / "remote.sh"
OCI_ID = "ocid1.vaultsecret.oc1.iad.synthetic.Created"
SAMPLE_VALUE = "synthetic-test-value"


class VaultSecretTaskTest(unittest.TestCase):
    def test_create_prompts_without_echo_and_persists_only_secret_ocid(self):
        with tempfile.TemporaryDirectory() as directory:
            # Keep the temporary project alive while the task executes.
            temp = Path(directory)
            project = temp / "repo" / "ford-ai-service"
            (project / ".mise" / "lib").mkdir(parents=True)
            (project / ".mise" / "lib" / "remote.sh").symlink_to(REMOTE_LIBRARY)
            (project / "scripts").symlink_to(REPO_ROOT / "scripts")
            fake_bin = temp / "bin"
            fake_bin.mkdir()
            fake_oci = fake_bin / "oci"
            fake_oci.write_text(
                "#!/usr/bin/env python3\n"
                "import base64, json, sys\n"
                "import os\n"
                "args = sys.argv[1:]\n"
                "if args[:3] == ['iam', 'region', 'list']:\n"
                "    print('us-ashburn-1')\n"
                "    raise SystemExit(0)\n"
                "payload = json.load(sys.stdin)\n"
                f"assert {SAMPLE_VALUE!r} not in os.environ.values()\n"
                f"assert base64.b64decode(payload['secretContentContent']) == {SAMPLE_VALUE.encode()!r}\n"
                "assert payload['secretName'] == 'ford-prod-openrouter-api-key'\n"
                "print(" + repr(OCI_ID) + ")\n",
                encoding="utf-8",
            )
            fake_oci.chmod(0o700)
            env = {
                **os.environ,
                "PATH": f"{fake_bin}:{os.environ['PATH']}",
                "MISE_PROJECT_ROOT": str(project),
                "OCI_REGION": "us-ashburn-1",
            }
            command = [str(TASK), "create", "OPENROUTER_API_KEY"]
            pid, master_fd = pty.fork()
            if pid == 0:
                os.execve(command[0], command, env)
            prompts = [
                b"OCI compartment OCID",
                b"OCI Vault OCID",
                b"OCI Vault key OCID",
                b"Enter the newly issued value",
                b"Confirm the value",
            ]
            answers = iter(
                value.encode() + b"\n"
                for value in (
                    "ocid1.compartment.oc1.iad.syntheticCompartment",
                    "ocid1.vault.oc1.iad.syntheticVault",
                    "ocid1.key.oc1.iad.syntheticKey",
                    SAMPLE_VALUE,
                    SAMPLE_VALUE,
                )
            )
            sent = 0
            output = bytearray()
            status = None
            deadline = time.monotonic() + 20
            try:
                while time.monotonic() < deadline:
                    ready, _, _ = select.select([master_fd], [], [], 0.2)
                    if ready:
                        try:
                            chunk = os.read(master_fd, 4096)
                        except OSError:
                            chunk = b""
                        if not chunk:
                            break
                        output.extend(chunk)
                        if sent < len(prompts) and prompts[sent] in output:
                            time.sleep(0.05)
                            os.write(master_fd, next(answers))
                            sent += 1
                    waited_pid, wait_status = os.waitpid(pid, os.WNOHANG)
                    if waited_pid == pid:
                        status = wait_status
                        break
                if status is None:
                    _, status = os.waitpid(pid, 0)
            finally:
                os.close(master_fd)

            self.assertEqual(sent, len(prompts))
            self.assertEqual(os.waitstatus_to_exitcode(status), 0, output.decode(errors="replace"))
            self.assertNotIn(SAMPLE_VALUE.encode(), output)
            mapping_file = temp / "repo" / "deployment" / "config" / "vault-secret-ocids.env"
            self.assertEqual(
                mapping_file.read_text(encoding="utf-8"),
                f"FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY={OCI_ID}\n",
            )
            self.assertEqual(mapping_file.stat().st_mode & 0o777, 0o600)

    def test_imports_current_config_value_to_test_vault_without_displaying_or_mapping_it_for_production(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            project = temp / "repo" / "ford-ai-service"
            (project / ".mise" / "lib").mkdir(parents=True)
            (project / ".mise" / "lib" / "remote.sh").symlink_to(REMOTE_LIBRARY)
            (project / "scripts").symlink_to(REPO_ROOT / "scripts")
            config = temp / "repo" / "deployment" / "config"
            config.mkdir(parents=True)
            (config / "oci.env").write_text(
                f"OPENROUTER_API_KEY='{SAMPLE_VALUE}'\n"
                f"GHCR_TOKEN='{SAMPLE_VALUE}'\n"
                f"GRAFANA_CLOUD_API_TOKEN='{SAMPLE_VALUE}'\n"
                f"LANGFUSE_SECRET_KEY='{SAMPLE_VALUE}'\n"
                "OCI_COMPARTMENT_OCID=''\nOCI_VAULT_ID=''\nOCI_VAULT_KEY_ID=''\n",
                encoding="utf-8",
            )

            fake_bin = temp / "bin"
            fake_bin.mkdir()
            fake_oci = fake_bin / "oci"
            fake_oci.write_text(
                "#!/usr/bin/env python3\n"
                "import base64, json, sys\n"
                "import os\n"
                "args = sys.argv[1:]\n"
                "if args[:3] == ['iam', 'region', 'list']:\n"
                "    print('us-ashburn-1')\n"
                "    raise SystemExit(0)\n"
                "payload = json.load(sys.stdin)\n"
                f"assert {SAMPLE_VALUE!r} not in os.environ.values()\n"
                f"assert base64.b64decode(payload['secretContentContent']) == {SAMPLE_VALUE.encode()!r}\n"
                "assert payload['secretName'] in {\n"
                "    'ford-test-openrouter-api-key', 'ford-test-ghcr-token',\n"
                "    'ford-test-grafana-cloud-api-token', 'ford-test-langfuse-secret-key'\n"
                "}\n"
                "print('ocid1.vaultsecret.oc1.iad.synthetic.' + payload['secretName'].replace('-', '_'))\n",
                encoding="utf-8",
            )
            fake_oci.chmod(0o700)
            env = {
                **os.environ,
                "PATH": f"{fake_bin}:{os.environ['PATH']}",
                "MISE_PROJECT_ROOT": str(project),
                "OCI_REGION": "us-ashburn-1",
                "OCI_COMPARTMENT_OCID": "ocid1.compartment.oc1.iad.syntheticCompartment",
                "OCI_VAULT_ID": "ocid1.vault.oc1.iad.syntheticVault",
                "OCI_VAULT_KEY_ID": "ocid1.key.oc1.iad.syntheticKey",
            }
            result = subprocess.run(
                [str(TASK), "import-current-test"],
                capture_output=True,
                text=True,
                env=env,
                check=False,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertNotIn(SAMPLE_VALUE, result.stdout + result.stderr)
            test_mapping = config / "vault-test-secret-ocids.env"
            self.assertEqual(
                test_mapping.read_text(encoding="utf-8"),
                "FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY="
                "ocid1.vaultsecret.oc1.iad.synthetic.ford_test_openrouter_api_key\n"
                "FORD_VAULT_SECRET_OCID_GHCR_TOKEN="
                "ocid1.vaultsecret.oc1.iad.synthetic.ford_test_ghcr_token\n"
                "FORD_VAULT_SECRET_OCID_GRAFANA_CLOUD_API_TOKEN="
                "ocid1.vaultsecret.oc1.iad.synthetic.ford_test_grafana_cloud_api_token\n"
                "FORD_VAULT_SECRET_OCID_LANGFUSE_SECRET_KEY="
                "ocid1.vaultsecret.oc1.iad.synthetic.ford_test_langfuse_secret_key\n",
            )
            self.assertEqual(test_mapping.stat().st_mode & 0o777, 0o600)
            self.assertFalse((config / "vault-secret-ocids.env").exists())


if __name__ == "__main__":
    unittest.main()
