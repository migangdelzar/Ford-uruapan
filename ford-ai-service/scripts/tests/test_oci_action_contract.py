import os
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
ACTION_SCRIPT = REPO_ROOT.parent / ".github/actions/oci-blue-green/deploy.sh"
ACTION_METADATA = REPO_ROOT.parent / ".github/actions/oci-blue-green/action.yml"
RELEASE_WORKFLOW = REPO_ROOT.parent / ".github/workflows/release.yml"
CONTAINER_WORKFLOW = REPO_ROOT.parent / ".github/workflows/reusable-container.yml"
CI_WORKFLOW = REPO_ROOT.parent / ".github/workflows/ci.yml"
PREFLIGHT_WORKFLOW = REPO_ROOT.parent / ".github/workflows/deployment-preflight.yml"


class OciActionContractTest(unittest.TestCase):
    def run_validation(self, **overrides: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update({
            "OCI_TARGET": "ford-service",
            "OCI_IMAGE": "ghcr.io/example/ford-service@sha256:" + "a" * 64,
            "OCI_OPERATION": "deploy",
            "OCI_SOURCE_SHA": "c" * 40,
            "OCI_DEPLOY_HOST": "203.0.113.10",
            "OCI_DEPLOY_USER": "deploy",
            "OCI_SSH_PRIVATE_KEY": "PRIVATE-KEY-SHOULD-NOT-LEAK",
            "OCI_SSH_KNOWN_HOSTS": "203.0.113.10 ssh-ed25519 PUBLIC-HOST-KEY",
            "OCI_BACKUP_SSH_PRIVATE_KEY": "BACKUP-PRIVATE-KEY-SHOULD-NOT-LEAK",
            "OCI_REGISTRY_USERNAME": "release-bot",
            "GHCR_TOKEN": "REGISTRY-TOKEN-SHOULD-NOT-LEAK",
            "OCI_REMOTE_ROOT": "/opt/ford",
            "OCI_BACKUP_HOST": "203.0.113.11",
            "OCI_BACKUP_USER": "deploy",
            "OCI_MCP_PRIVATE_IP": "10.0.0.2",
            "OCI_ACTION_VALIDATE_ONLY": "true",
        })
        environment.update(overrides)
        return subprocess.run(
            ["bash", str(ACTION_SCRIPT)],
            cwd=REPO_ROOT.parent,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_valid_target_and_immutable_digest_pass_input_validation(self):
        result = self.run_validation()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_action_rejects_unknown_target(self):
        result = self.run_validation(OCI_TARGET="unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("target must be ford-service or ford-mcp", result.stderr)

    def test_ford_service_requires_an_existing_backup_host(self):
        result = self.run_validation(OCI_BACKUP_HOST="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("existing data VM backup host", result.stderr)

        rollback = self.run_validation(
            OCI_OPERATION="rollback",
            OCI_SOURCE_SHA="recorded-rollback",
            OCI_IMAGE="",
            OCI_BACKUP_HOST="",
        )
        self.assertEqual(rollback.returncode, 0, rollback.stderr)

    def test_ford_service_deploy_requires_a_separate_backup_host_key(self):
        deploy = self.run_validation(OCI_BACKUP_SSH_PRIVATE_KEY="")
        self.assertNotEqual(deploy.returncode, 0)
        self.assertIn("backup-ssh-private-key is required", deploy.stderr)

        rollback = self.run_validation(
            OCI_OPERATION="rollback",
            OCI_SOURCE_SHA="recorded-rollback",
            OCI_IMAGE="",
            OCI_BACKUP_SSH_PRIVATE_KEY="",
        )
        self.assertEqual(rollback.returncode, 0, rollback.stderr)

    def test_action_rejects_missing_or_mutable_deploy_images(self):
        for image in ("", "ghcr.io/example/ford-service:latest"):
            with self.subTest(image=image):
                result = self.run_validation(OCI_IMAGE=image)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("immutable GHCR image digest", result.stderr)

    def test_mcp_requires_existing_private_ip_and_rollback_resolves_recorded_digest(self):
        missing_ip = self.run_validation(OCI_TARGET="ford-mcp", OCI_MCP_PRIVATE_IP="")
        self.assertNotEqual(missing_ip.returncode, 0)
        self.assertIn("existing VM private IPv4", missing_ip.stderr)

        rollback = self.run_validation(OCI_TARGET="ford-mcp", OCI_OPERATION="rollback", OCI_SOURCE_SHA="recorded-rollback", OCI_IMAGE="")
        self.assertEqual(rollback.returncode, 0, rollback.stderr)

    def test_validation_never_prints_deployment_secrets(self):
        result = self.run_validation(OCI_TARGET="unknown")
        self.assertNotIn("PRIVATE-KEY-SHOULD-NOT-LEAK", result.stdout + result.stderr)
        self.assertNotIn("BACKUP-PRIVATE-KEY-SHOULD-NOT-LEAK", result.stdout + result.stderr)
        self.assertNotIn("REGISTRY-TOKEN-SHOULD-NOT-LEAK", result.stdout + result.stderr)

    def test_composite_action_uses_explicit_inputs_and_a_local_playbook_runner(self):
        action = ACTION_METADATA.read_text(encoding="utf-8")
        self.assertIn("target:", action)
        self.assertIn("registry-token:", action)
        self.assertIn("ssh-known-hosts:", action)
        self.assertIn("backup-ssh-private-key:", action)
        self.assertIn("backup-host:", action)
        self.assertIn("${{ github.action_path }}/deploy.sh", action)
        self.assertNotIn("ssh-keyscan", action)

    def test_release_workflow_keeps_host_configuration_in_variables_and_keys_in_secrets(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        for name in ("OCI_DEPLOY_HOST", "OCI_DEPLOY_USER", "OCI_DATA_DEPLOY_HOST", "OCI_DATA_DEPLOY_USER"):
            self.assertIn(f"vars.{name}", workflow)
            self.assertNotIn(f"secrets.{name}", workflow)
        self.assertIn("secrets.OCI_SSH_PRIVATE_KEY", workflow)
        self.assertIn("secrets.OCI_DATA_SSH_PRIVATE_KEY", workflow)
        self.assertIn("secrets.OCI_SSH_KNOWN_HOSTS", workflow)

    def test_release_workflow_is_closed_until_vault_and_production_gates_pass(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("vars.PRODUCTION_DEPLOY_ENABLED == 'true'", workflow)
        self.assertIn("vars.RUNTIME_VAULT_READY == 'true'", workflow)
        self.assertIn("environment: production", workflow)
        self.assertIn("options: [deploy, rollback]", workflow)
        self.assertIn("--status success", workflow)
        self.assertIn("source-sha:", workflow)

    def test_reusable_container_exposes_the_published_digest(self):
        workflow = CONTAINER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("jobs.image.outputs.image", workflow)
        self.assertIn("jobs.image.outputs.digest", workflow)
        self.assertIn("@${IMAGE_DIGEST}", workflow)

    def test_container_publication_is_restricted_to_main(self):
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("github.ref == 'refs/heads/main' && inputs.publish_container == true", workflow)

    def test_vm_preflight_is_branch_scoped_and_read_only(self):
        workflow = PREFLIGHT_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("chore/platform-secret-management", workflow)
        self.assertIn("environment: deployment-preflight", workflow)
        self.assertIn("/actuator/health", workflow)
        self.assertIn("ford-mcp-mcp-1", workflow)
        self.assertIn("data-postgres-1", workflow)
        self.assertNotIn("docker compose up", workflow)
        self.assertNotIn("docker stop", workflow)
        self.assertNotIn("migrate", workflow)


if __name__ == "__main__":
    unittest.main()
