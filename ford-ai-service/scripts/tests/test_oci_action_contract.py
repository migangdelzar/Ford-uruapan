import os
import json
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
BACKUP_WORKFLOW = REPO_ROOT.parent / ".github/workflows/database-backup.yml"


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
        self.assertIn("target must be ford-service or ford-scraper-scheduler", result.stderr)

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

    def test_retired_mcp_target_is_rejected_and_scheduler_target_is_supported(self):
        retired = self.run_validation(OCI_TARGET="ford-mcp")
        self.assertNotEqual(retired.returncode, 0)
        self.assertIn("target must be ford-service or ford-scraper-scheduler", retired.stderr)

        scheduler = self.run_validation(OCI_TARGET="ford-scraper-scheduler")
        self.assertEqual(scheduler.returncode, 0, scheduler.stderr)

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

    def test_release_workflow_passes_the_ui_upstream_override_for_cutover_and_rollback(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("ui-upstream-url: ${{ vars.FORD_WEB_UI_UPSTREAM_URL }}", workflow)

    def test_release_workflow_requires_and_passes_meta_reply_delivery_configuration(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        for name in (
            "FORD_META_DELIVERY_ENABLED",
            "FORD_OUTBOX_ENABLED",
            "FORD_META_WHATSAPP_DELIVERY_ENDPOINT",
            "FORD_META_PHONE_NUMBER_ID",
            "FORD_VAULT_SECRET_OCID_FORD_META_APP_SECRET",
            "FORD_VAULT_SECRET_OCID_FORD_META_VERIFY_TOKEN",
            "FORD_VAULT_SECRET_OCID_FORD_META_WHATSAPP_ACCESS_TOKEN",
        ):
            with self.subTest(name=name):
                self.assertIn(f"{name}: ${{{{ vars.{name} }}}}", workflow)
                self.assertIn(name, workflow.split("Validate protected runtime Vault configuration", 1)[1])

        mappings = {
            "meta-delivery-enabled:": "FORD_META_DELIVERY_ENABLED",
            "outbox-enabled:": "FORD_OUTBOX_ENABLED",
            "meta-whatsapp-delivery-endpoint:": "FORD_META_WHATSAPP_DELIVERY_ENDPOINT",
            "meta-phone-number-id:": "FORD_META_PHONE_NUMBER_ID",
            "meta-app-secret-ocid:": "FORD_VAULT_SECRET_OCID_FORD_META_APP_SECRET",
            "meta-verify-token-ocid:": "FORD_VAULT_SECRET_OCID_FORD_META_VERIFY_TOKEN",
            "meta-whatsapp-access-token-secret-ocid:": "FORD_VAULT_SECRET_OCID_FORD_META_WHATSAPP_ACCESS_TOKEN",
        }
        for input_name, variable in mappings.items():
            with self.subTest(input_name=input_name):
                self.assertIn(f"{input_name} ${{{{ vars.{variable} }}}}", workflow)

        self.assertIn(r"graph\.facebook\.com", workflow)
        self.assertIn('"$FORD_OUTBOX_ENABLED" == \'true\'', workflow)
        self.assertIn('"$FORD_META_DELIVERY_ENABLED" == \'true\'', workflow)

    def test_customer_memory_vault_reference_is_scoped_and_disabled_until_ready(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        app_template = (REPO_ROOT.parent / "deployment/ansible/roles/app/templates/app.env.j2").read_text(encoding="utf-8")
        policy = json.loads((REPO_ROOT.parent / "deployment/terraform/runtime-vault-policy.json").read_text(encoding="utf-8"))
        mapping_example = (REPO_ROOT.parent / "deployment/config/vault-secret-ocids.env.example").read_text(encoding="utf-8")

        self.assertIn("FORD_CUSTOMER_MEMORY_ENABLED: ${{ vars.FORD_CUSTOMER_MEMORY_ENABLED || 'false' }}", workflow)
        self.assertIn("FORD_CUSTOMER_MEMORY_READY: ${{ vars.FORD_CUSTOMER_MEMORY_READY || 'false' }}", workflow)
        self.assertIn(
            "FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION: ${{ vars.FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION || '1' }}",
            workflow,
        )
        self.assertIn(
            "FORD_VAULT_SECRET_OCID_FORD_CUSTOMER_MEMORY_HMAC_KEY: ${{ vars.FORD_VAULT_SECRET_OCID_FORD_CUSTOMER_MEMORY_HMAC_KEY }}",
            workflow,
        )
        self.assertIn(
            "customer-memory-enabled: ${{ vars.FORD_CUSTOMER_MEMORY_ENABLED || 'false' }}",
            workflow,
        )
        self.assertIn(
            "customer-memory-vault-secret-ocid: ${{ vars.FORD_VAULT_SECRET_OCID_FORD_CUSTOMER_MEMORY_HMAC_KEY }}",
            workflow,
        )
        self.assertIn(
            "customer-memory-key-version: ${{ vars.FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION || '1' }}",
            workflow,
        )
        validation = workflow.split("Validate protected runtime Vault configuration", 1)[1]
        self.assertIn('"$FORD_CUSTOMER_MEMORY_ENABLED" == \'false\'', validation)
        self.assertIn('"$FORD_CUSTOMER_MEMORY_READY" == \'true\'', validation)
        self.assertIn("FORD_CUSTOMER_MEMORY_HMAC_KEY", validation)
        self.assertIn('"$FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION" =~ ^[1-9][0-9]*$', validation)

        self.assertIn("FORD_CUSTOMER_MEMORY_ENABLED=", app_template)
        self.assertIn("FORD_VAULT_SECRET_OCID_FORD_CUSTOMER_MEMORY_HMAC_KEY=", app_template)
        self.assertIn("FORD_VAULT_SECRET_OCID_FORD_CUSTOMER_MEMORY_HMAC_KEY=", mapping_example)

        memory_secret = "ford-prod-ford-customer-memory-hmac-key"
        self.assertIn(memory_secret, policy["secret_names"]["app"])
        self.assertNotIn(memory_secret, policy["secret_names"]["data"])

    def test_release_workflow_pins_the_meta_aware_shared_action_commit(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "migangdelzar/Ford-oci-deployment-action/.github/actions/oci-blue-green@d391f130a0d820819ee4f38c66d5a2d12d823cad",
            workflow,
        )

    def test_service_release_wires_local_catalog_reader_and_removes_remote_mcp_client(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        app_template = (REPO_ROOT.parent / "deployment/ansible/roles/app/templates/app.env.j2").read_text(encoding="utf-8")
        action_template = (REPO_ROOT.parent / ".github/actions/oci-blue-green/deploy.sh").read_text(encoding="utf-8")

        for name in (
            "FORD_CATALOG_TOOLS_ENABLED",
            "FORD_CATALOG_JDBC_URL",
            "FORD_CATALOG_USERNAME",
        ):
            self.assertIn(name, workflow + action_template)
        self.assertNotIn("FORD_MCP_", workflow)
        self.assertNotIn("service-mcp-", workflow)
        self.assertNotIn("FORD_MCP_", app_template)

    def test_release_workflow_is_closed_until_vault_and_production_gates_pass(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("vars.PRODUCTION_DEPLOY_ENABLED == 'true'", workflow)
        self.assertIn("name: ford-release-result", workflow)
        self.assertIn("ford.emme.business", workflow)
        self.assertIn("vars.RUNTIME_VAULT_READY == 'true'", workflow)
        self.assertIn("environment: production", workflow)
        self.assertIn("options: [deploy, rollback]", workflow)
        self.assertIn("status=success", workflow)
        self.assertIn("source-sha:", workflow)

    def test_service_ci_publishes_only_after_a_semver_tag_passes_ci(self):
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        container = CONTAINER_WORKFLOW.read_text(encoding="utf-8")

        self.assertNotIn("publish_container", workflow)
        self.assertIn("inputs.version", container)
        self.assertIn("publish: false", workflow)
        self.assertIn("github.ref_type == 'tag'", workflow)
        self.assertIn("refs/tags/v", workflow)
        self.assertIn("Container gates (no publication)", workflow)
        self.assertIn("Container gates and version publication", workflow)
        self.assertIn("packages: write", workflow)
        self.assertIn("git merge-base --is-ancestor", container)
        self.assertIn("git cat-file -t", container)
        self.assertIn("docker buildx imagetools inspect", container)
        self.assertIn("${VERSION#v}", container)

    def test_service_release_resolves_version_tag_and_deploys_only_attested_digest(self):
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("version:", workflow)
        self.assertIn("INPUT_VERSION", workflow)
        self.assertIn("refs/tags/${VERSION}", workflow)
        self.assertIn("--source-ref", workflow)
        self.assertIn("--source-digest", workflow)
        self.assertIn("sha256:[a-f0-9]{64}", workflow)
        self.assertIn("vars.PRODUCTION_DEPLOY_ENABLED == 'true'", workflow)

    def test_reusable_container_exposes_the_published_digest(self):
        workflow = CONTAINER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("jobs.image.outputs.image", workflow)
        self.assertIn("jobs.image.outputs.digest", workflow)
        self.assertIn("@${IMAGE_DIGEST}", workflow)

    def test_container_publication_is_restricted_to_semver_tags(self):
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("publish_container", workflow)
        self.assertIn("github.event_name == 'push'", workflow)
        self.assertIn("github.ref_type == 'tag'", workflow)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", workflow)

    def test_vm_preflight_is_branch_scoped_and_read_only(self):
        workflow = PREFLIGHT_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("chore/platform-secret-management", workflow)
        self.assertIn("environment: deployment-preflight", workflow)
        self.assertIn("/actuator/health", workflow)
        app_check = workflow.split("- name: Verify Ford Service and collector host health on emme-1", 1)[1].split(
            "- name: Verify PostgreSQL host health on emme-2", 1
        )[0]
        self.assertIn("Health probe port", app_check)
        self.assertIn("status={status}", app_check)
        self.assertNotIn('payload.get("components"', app_check)
        self.assertIn("Service containers:", app_check)
        self.assertLess(app_check.index("Service containers:"), app_check.index("exit 1"))
        self.assertIn("for port in (8080, 18081, 18082)", app_check)
        database_check = workflow.split("- name: Verify PostgreSQL host health on emme-2", 1)[1].split(
            "- name: Summarize read-only preflight", 1
        )[0]
        self.assertIn("if: always()", database_check)
        self.assertNotIn("ford-mcp-mcp-1", workflow)
        self.assertIn("data-postgres-1", workflow)
        self.assertNotIn("docker compose up", workflow)
        self.assertNotIn("docker stop", workflow)
        self.assertNotIn("migrate", workflow)

    def test_database_backup_workflow_supports_manual_run_and_scheduled_verification(self):
        workflow = BACKUP_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("cron: '30 4 1 * *'", workflow)
        self.assertIn("options: [run, verify]", workflow)
        self.assertIn("environment: deployment-preflight", workflow)
        self.assertIn("secrets.DATA_SSH_PRIVATE_KEY", workflow)
        self.assertIn("secrets.DATA_SSH_KNOWN_HOSTS", workflow)
        self.assertIn("vars.DATA_SSH_HOST", workflow)
        self.assertIn("vars.OCI_SSH_USER", workflow)
        manual_run = workflow.split("- name: Run a backup now", maxsplit=1)[1].split(
            "- name: Verify the scheduled backup timer and recent backup", maxsplit=1
        )[0]
        scheduled_check = workflow.split(
            "- name: Verify the scheduled backup timer and recent backup", maxsplit=1
        )[1]
        self.assertIn("sudo -n systemctl start ford-backup.service", manual_run)
        self.assertNotIn("systemctl start", scheduled_check)
        self.assertIn("github.event_name == 'schedule'", scheduled_check)
        self.assertIn("systemctl is-enabled ford-backup.timer", workflow)
        self.assertIn("systemctl is-active ford-backup.timer", workflow)
        self.assertIn("manifest_*.json", workflow)
        self.assertIn("MAX_BACKUP_AGE_SECONDS", scheduled_check)
        self.assertIn("'129600'", scheduled_check)
        self.assertIn("'2764800'", scheduled_check)
        self.assertIn('"$MAX_BACKUP_AGE_SECONDS"', scheduled_check)
        self.assertIn("StrictHostKeyChecking=yes", workflow)
        self.assertNotIn("actions/upload-artifact", workflow)
        self.assertNotIn("pg_dump", workflow)
        self.assertNotIn("systemctl enable", workflow)


if __name__ == "__main__":
    unittest.main()
