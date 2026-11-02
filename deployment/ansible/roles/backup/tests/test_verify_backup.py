import hashlib
import importlib.util
import json
import tempfile
import os
import re
import subprocess
import unittest
from pathlib import Path


VERIFIER = Path(__file__).resolve().parents[2] / "catalog_sync/files/verify_backup.py"
SPEC = importlib.util.spec_from_file_location("verify_backup", VERIFIER)
verify_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_module)
verify_backup = verify_module.verify_backup


class VerifyBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.archive = Path(self.temp.name)
        self.created_at = "20261102T120000Z"
        self.postgres = b"synthetic postgres dump"
        self.redis = b"synthetic redis dump"
        (self.archive / "postgres.dump.gz").write_bytes(self.postgres)
        (self.archive / "redis.rdb.gz").write_bytes(self.redis)
        self.write_manifest("ford_service")

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self, db_name, **overrides):
        manifest = {
            "created_at": self.created_at,
            "database": db_name,
            "postgres": "postgres.dump.gz",
            "postgres_sha256": hashlib.sha256(self.postgres).hexdigest(),
            "redis": "redis.rdb.gz",
            "redis_sha256": hashlib.sha256(self.redis).hexdigest(),
        }
        manifest.update(overrides)
        (self.archive / f"manifest_{self.created_at}.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )

    def test_valid_legacy_and_candidate_manifests_are_distinguishable(self):
        self.assertEqual(verify_backup(self.archive, "20261102"), {"backupCreatedAt": self.created_at})
        self.write_manifest("ford")
        verify_backup(self.archive, "20261102", expected_database="ford")
        with self.assertRaisesRegex(ValueError, "database"):
            verify_backup(self.archive, "20261102", expected_database="ford_service")

    def test_historical_manifest_without_database_is_legacy(self):
        self.write_manifest("ford_service")
        manifest_path = self.archive / f"manifest_{self.created_at}.json"
        manifest = json.loads(manifest_path.read_text())
        del manifest["database"]
        manifest_path.write_text(json.dumps(manifest))
        self.assertEqual(verify_backup(self.archive, "20261102"), {"backupCreatedAt": self.created_at})
        with self.assertRaisesRegex(ValueError, "database"):
            verify_backup(self.archive, "20261102", expected_database="ford")

    def test_rejects_unapproved_database(self):
        self.write_manifest("production")
        with self.assertRaisesRegex(ValueError, "database"):
            verify_backup(self.archive, "20261102")

    def test_rejects_invalid_checksum(self):
        self.write_manifest("ford_service", redis_sha256="not-a-hash")
        with self.assertRaisesRegex(ValueError, "checksum is invalid"):
            verify_backup(self.archive, "20261102")

    def test_rejects_archive_not_named_by_manifest(self):
        with self.assertRaisesRegex(ValueError, "does not match requested archive"):
            verify_module.verify_backup(
                self.archive, "20261102", expected_archive="other_20261102T120000Z.dump.gz"
            )

    def test_rejects_missing_pair_member(self):
        (self.archive / "redis.rdb.gz").unlink()
        with self.assertRaisesRegex(ValueError, "redis backup artifact is missing"):
            verify_backup(self.archive, "20261102")

    def test_rejects_stale_or_mismatched_manifest_identity(self):
        with self.assertRaisesRegex(ValueError, "manifest is missing"):
            verify_backup(self.archive, "20261103")
        self.write_manifest("ford_service", created_at="20261101T120000Z")
        with self.assertRaisesRegex(ValueError, "timestamp"):
            verify_backup(self.archive, "20261102")

    def test_restore_template_requires_production_approval_and_compose_rollback(self):
        template = (VERIFIER.parents[2] / "backup/templates/restore-database.sh.j2").read_text()
        self.assertIn('FORD_RESTORE_APPROVAL_ID', template)
        self.assertIn('FORD_RESTORE_TARGET', template)
        self.assertIn('"emme-2"', template)
        self.assertIn('docker compose', template)
        self.assertIn('"ford_service"', template)
        self.assertIn('"ford"', template)

    def test_restore_wrapper_is_explicit_approval_gated(self):
        wrapper = (VERIFIER.parents[5] / "ford-ai-service/.mise/tasks/backup/restore").read_text()
        self.assertIn('FORD_RESTORE_APPROVAL_ID', wrapper)
        self.assertIn('FORD_RESTORE_TARGET', wrapper)
        self.assertIn('ansible data', wrapper)

    def test_production_restore_refuses_missing_and_mismatched_gates(self):
        template = (VERIFIER.parents[2] / "backup/templates/restore-database.sh.j2").read_text()
        script = template.replace('{{ remote_root }}', self.temp.name)
        script_path = self.archive / "restore.sh"
        script_path.write_text(script)
        script_path.chmod(0o755)
        args = ["approval-123", "postgres.dump.gz", "ford"]
        base_env = dict(os.environ, FORD_RESTORE_APPROVAL_ID="approval-123", FORD_RESTORE_TARGET="emme-2")
        cases = [
            ({"FORD_RESTORE_APPROVAL_ID": ""}, "approval"),
            ({"FORD_RESTORE_APPROVAL_ID": "another-id"}, "approval"),
            ({"FORD_RESTORE_TARGET": "other-host"}, "target"),
            ({}, "archive"),
            ({}, "database"),
        ]
        for overrides, _message in cases:
            env = dict(base_env)
            env.update(overrides)
            invocation = args[:]
            if _message == "archive":
                invocation[1] = ""
            if _message == "database":
                invocation[2] = "production"
            result = subprocess.run([str(script_path), *invocation], env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)

    def test_isolated_restore_requires_local_guardrails(self):
        root = Path(__file__).resolve().parents[5]
        script = root / "scripts/backup/restore-rehearsal.sh"
        wrapper = root / "ford-ai-service/.mise/tasks/backup/restore-isolated"
        source = script.read_text()
        self.assertIn("DOCKER_HOST", source)
        self.assertIn("ford.rehearsal=isolated", source)
        self.assertIn("docker exec", source)
        self.assertNotIn("ansible ", source.lower())
        self.assertNotRegex(source.lower(), re.compile(r"(?m)^\s*ssh\s"))
        self.assertTrue(wrapper.exists())


if __name__ == "__main__":
    unittest.main()
