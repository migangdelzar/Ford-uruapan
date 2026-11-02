import hashlib
import importlib.util
import json
import tempfile
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

    def test_rejects_missing_manifest_database(self):
        self.write_manifest(None, database=None)
        with self.assertRaisesRegex(ValueError, "database"):
            verify_backup(self.archive, "20261102")

    def test_rejects_unapproved_database(self):
        self.write_manifest("production")
        with self.assertRaisesRegex(ValueError, "database"):
            verify_backup(self.archive, "20261102")

    def test_rejects_invalid_checksum(self):
        self.write_manifest("ford_service", redis_sha256="not-a-hash")
        with self.assertRaisesRegex(ValueError, "checksum is invalid"):
            verify_backup(self.archive, "20261102")

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

    def test_restore_template_requires_isolated_target_without_compose_restore(self):
        template = (VERIFIER.parents[2] / "backup/templates/restore-database.sh.j2").read_text()
        self.assertIn('database_name="${3:-}"', template)
        self.assertIn('"$database_name" != "ford_service"', template)
        self.assertIn('"$database_name" != "ford"', template)
        self.assertIn('docker exec -i "$container_name"', template)
        self.assertIn('^rehearsal-', template)
        self.assertNotIn("docker compose", template)


if __name__ == "__main__":
    unittest.main()
