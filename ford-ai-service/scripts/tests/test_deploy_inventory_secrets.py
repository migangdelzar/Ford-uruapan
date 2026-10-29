import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
REMOTE_LIBRARY = REPO_ROOT / ".mise" / "lib" / "remote.sh"


class DeployInventorySecretsTest(unittest.TestCase):
    def test_generated_inventory_does_not_persist_secret_values(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            inventory = temp / "inventory.yml"
            env = {
                **os.environ,
                "MISE_PROJECT_ROOT": str(temp / "isolated-project"),
                "FORD_DEPLOY_INVENTORY_FILE": str(inventory),
                "OCI_SSH_USER": "opc",
                "OCI_SSH_PRIVATE_KEY": "/tmp/ssh-key-path",
                "EMME_1_SSH_HOST": "app.internal",
                "EMME_2_SSH_HOST": "data.internal",
                "EMME_1_PRIVATE_IP": "10.0.0.1",
                "EMME_2_PRIVATE_IP": "10.0.0.2",
                "EMME_REMOTE_ROOT": "/opt/ford",
                "EMME_DATA_VOLUME_MOUNT": "/var/lib/ford",
                "FORD_OPERATOR_USERNAME": "operator",
                "GRAFANA_CLOUD_OTLP_ENDPOINT": "https://example.invalid/otlp",
                "GRAFANA_CLOUD_INSTANCE_ID": "synthetic-instance",
                "LANGFUSE_PUBLIC_KEY": "synthetic-public-key",
                "OCI_REGION": "us-ashburn-1",
                "POSTGRES_PASSWORD": "synthetic-postgres-value",
                "REDIS_PASSWORD": "synthetic-redis-value",
                "FORD_OPERATOR_PASSWORD": "synthetic-operator-value",
                "FORD_META_APP_SECRET": "synthetic-meta-value",
                "FORD_META_VERIFY_TOKEN": "synthetic-verify-value",
                "OPENROUTER_API_KEY": "synthetic-openrouter-value",
                "GHCR_TOKEN": "synthetic-ghcr-value",
                "GRAFANA_CLOUD_API_TOKEN": "synthetic-grafana-value",
                "LANGFUSE_SECRET_KEY": "synthetic-langfuse-value",
                "MCP_HTTP_BEARER_TOKEN": "synthetic-mcp-value",
            }
            command = (
                f"source {str(REMOTE_LIBRARY)!r}; "
                "render_deploy_inventory"
            )
            subprocess.run(["bash", "-c", command], env=env, check=True, capture_output=True)

            generated = inventory.read_text(encoding="utf-8")
            for value in (
                "synthetic-postgres-value",
                "synthetic-redis-value",
                "synthetic-operator-value",
                "synthetic-meta-value",
                "synthetic-verify-value",
                "synthetic-openrouter-value",
                "synthetic-ghcr-value",
                "synthetic-grafana-value",
                "synthetic-langfuse-value",
                "synthetic-mcp-value",
            ):
                self.assertNotIn(value, generated)
            for field in (
                "postgres_password:",
                "redis_password:",
                "ford_operator_password:",
                "openrouter_api_key:",
                "ghcr_token:",
                "grafana_cloud_api_token:",
                "langfuse_secret_key:",
                "mcp_http_bearer_token:",
            ):
                self.assertNotIn(field, generated)
            self.assertEqual(inventory.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
