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

    def test_production_restore_and_remote_helper_are_inert(self):
        root = Path(__file__).resolve().parents[5]
        template = (VERIFIER.parents[2] / "backup/templates/restore-database.sh.j2").read_text()
        wrapper_path = root / "ford-ai-service/.mise/tasks/backup/restore"
        wrapper = wrapper_path.read_text()

        self.assertNotRegex(template, re.compile(r"pg_restore.*--clean", re.DOTALL))
        self.assertNotIn("docker compose", template.lower())
        self.assertNotIn("pg_restore", template)
        self.assertIn("disabled", template.lower())
        helper_path = self.archive / "restore-database.sh"
        helper_path.write_text(template)
        helper_path.chmod(0o755)
        helper_result = subprocess.run([str(helper_path)], capture_output=True, text=True)
        self.assertNotEqual(helper_result.returncode, 0)
        self.assertIn("disabled", helper_result.stderr.lower())
        self.assertNotIn("ansible ", wrapper.lower())
        self.assertNotIn("ssh ", wrapper.lower())
        result = subprocess.run([str(wrapper_path)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("disabled", result.stderr.lower())

    def test_rehearsal_route_cannot_invoke_production_or_remote_restore(self):
        root = Path(__file__).resolve().parents[5]
        script = (root / "scripts/backup/restore-rehearsal.sh").read_text().lower()
        wrapper = (root / "ford-ai-service/.mise/tasks/backup/restore-isolated").read_text().lower()
        route = script + "\n" + wrapper
        self.assertNotIn("docker compose", route)
        self.assertNotIn("ansible ", route)
        self.assertNotRegex(route, re.compile(r"(?m)^\s*ssh\s"))

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
