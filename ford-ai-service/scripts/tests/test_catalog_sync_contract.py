import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT.parent / ".github/workflows/monthly-catalog-sync.yml"
PLAYBOOK = REPOSITORY_ROOT.parent / "deployment/ansible/catalog-sync.yml"
ROLE = REPOSITORY_ROOT.parent / "deployment/ansible/roles/catalog_sync/tasks/main.yml"
COMPOSE = REPOSITORY_ROOT.parent / "deployment/ansible/roles/catalog_sync/templates/compose.yml.j2"
BACKUP_VERIFIER = REPOSITORY_ROOT.parent / "deployment/ansible/roles/catalog_sync/files/verify_backup.py"
LOCAL_TASK = REPOSITORY_ROOT / ".mise/tasks/deploy/catalog-sync"
REMOTE_HELPERS = REPOSITORY_ROOT / ".mise/lib/remote.sh"


class CatalogSyncContractTest(unittest.TestCase):
    def test_local_mise_task_reuses_the_protected_ansible_playbook(self):
        task = LOCAL_TASK.read_text(encoding="utf-8")
        helpers = REMOTE_HELPERS.read_text(encoding="utf-8")
        for contract in (
            "load_deploy_config",
            "render_backup_inventory",
            "catalog-sync.yml",
            "--private-key",
            "EMME_2_SSH_KEY",
            "ansible-playbook",
        ):
            self.assertIn(contract, task)
        self.assertIn("backup_inventory_file()", helpers)
        self.assertNotIn("require_deploy_config", task)
        self.assertNotIn("mcp.yml", task)
        self.assertNotIn("migrate", task)
        self.assertTrue(LOCAL_TASK.stat().st_mode & 0o111)

    def test_playbook_requires_same_day_backup_manifest_and_verified_artifacts(self):
        role = ROLE.read_text(encoding="utf-8")
        verifier = BACKUP_VERIFIER.read_text(encoding="utf-8")
        for contract in (
            "verify_backup.py",
            "backup/archive",
            "catalog_sync_backup_check.rc",
        ):
            self.assertIn(contract, role)
        for contract in ("manifest_", "created_at", "postgres_sha256", "redis_sha256", "sha256", "is_symlink"):
            self.assertIn(contract, verifier)

    def test_sync_uses_promoted_immutable_image_and_private_one_shot_command(self):
        role = ROLE.read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        for contract in (
            "scheduler-release.json",
            "active_image",
            "sha256:",
            "docker",
            "compose",
            "--rm",
            "dist/src/cli.js",
            "sync",
            "failed_when",
            "pagesFailed",
            "embeddingFailures",
            "no_log",
        ):
            self.assertIn(contract, role)
        self.assertIn("data_default", compose)
        self.assertNotIn("migrate", role)

    def test_workflow_is_protected_minimal_and_does_not_receive_database_credentials(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        for contract in (
            "17 18 1 * *",
            "workflow_dispatch:",
            "refs/heads/main",
            "environment: deployment-preflight",
            "contents: read",
            "cancel-in-progress: false",
            "vars.FORD_CATALOG_SYNC_ENABLED == 'true'",
            "DATA_SSH_PRIVATE_KEY",
            "DATA_SSH_KNOWN_HOSTS",
            "GITHUB_STEP_SUMMARY",
            "if: always()",
        ):
            self.assertIn(contract, workflow)
        for forbidden in ("DATABASE_URL", "POSTGRES_PASSWORD", "FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD"):
            self.assertNotIn(forbidden, workflow)


if __name__ == "__main__":
    unittest.main()
