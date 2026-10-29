import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = REPOSITORY_ROOT.parent / "deployment/ansible/roles/catalog_sync/files/verify_backup.py"
sys.path.insert(0, str(VERIFIER.parent))

from verify_backup import verify_backup  # noqa: E402


class CatalogBackupVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.archive = Path(self.temporary_directory.name)
        self.created_at = "20261017T043000Z"
        self.postgres = b"postgres archive"
        self.redis = b"redis archive"
        (self.archive / "postgres.dump.gz").write_bytes(self.postgres)
        (self.archive / "redis.rdb.gz").write_bytes(self.redis)
        self.manifest_path = self.archive / f"manifest_{self.created_at}.json"
        self.write_manifest()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def write_manifest(self, **overrides):
        manifest = {
            "created_at": self.created_at,
            "postgres": "postgres.dump.gz",
            "postgres_sha256": hashlib.sha256(self.postgres).hexdigest(),
            "redis": "redis.rdb.gz",
            "redis_sha256": hashlib.sha256(self.redis).hexdigest(),
        }
        manifest.update(overrides)
        self.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def test_accepts_same_day_manifest_with_both_matching_artifacts(self):
        self.assertEqual(
            verify_backup(self.archive, "20261017"),
            {"backupCreatedAt": self.created_at},
        )

    def test_rejects_manifest_from_another_utc_day(self):
        with self.assertRaisesRegex(ValueError, "manifest is missing"):
            verify_backup(self.archive, "20261018")

    def test_rejects_missing_backup_artifact(self):
        (self.archive / "redis.rdb.gz").unlink()
        with self.assertRaisesRegex(ValueError, "redis backup artifact is missing"):
            verify_backup(self.archive, "20261017")

    def test_rejects_artifact_checksum_mismatch(self):
        (self.archive / "postgres.dump.gz").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "postgres backup checksum does not match"):
            verify_backup(self.archive, "20261017")

    def test_rejects_artifact_path_traversal(self):
        self.write_manifest(postgres="../postgres.dump.gz")
        with self.assertRaisesRegex(ValueError, "postgres backup filename is invalid"):
            verify_backup(self.archive, "20261017")


if __name__ == "__main__":
    unittest.main()
