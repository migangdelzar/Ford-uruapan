#!/usr/bin/env python3
"""Verify the newest local backup manifest and both referenced artifacts."""

import hashlib
import json
import re
import stat
import sys
from datetime import datetime
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_backup(archive: Path, utc_day: str) -> dict[str, str]:
    if not re.fullmatch(r"[0-9]{8}", utc_day):
        raise ValueError("expected UTC date must be YYYYMMDD")
    manifests = sorted(archive.glob(f"manifest_{utc_day}T*.json"))
    if not manifests:
        raise ValueError("same-day backup manifest is missing")

    manifest_path = manifests[-1]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    created_at = manifest.get("created_at")
    if not isinstance(created_at, str) or not re.fullmatch(
        rf"{utc_day}T[0-9]{{6}}Z", created_at
    ):
        raise ValueError("backup manifest timestamp is invalid")
    datetime.strptime(created_at, "%Y%m%dT%H%M%SZ")
    if manifest_path.name != f"manifest_{created_at}.json":
        raise ValueError("backup manifest filename does not match its timestamp")

    for key, digest_key in (
        ("postgres", "postgres_sha256"),
        ("redis", "redis_sha256"),
    ):
        filename = manifest.get(key)
        expected_digest = manifest.get(digest_key)
        if (
            not isinstance(filename, str)
            or Path(filename).name != filename
            or not filename
        ):
            raise ValueError(f"{key} backup filename is invalid")
        if not isinstance(expected_digest, str) or not re.fullmatch(
            r"[a-f0-9]{64}", expected_digest
        ):
            raise ValueError(f"{key} backup checksum is invalid")
        artifact_path = archive / filename
        if artifact_path.is_symlink() or not artifact_path.exists():
            raise ValueError(f"{key} backup artifact is missing or not regular")
        if not stat.S_ISREG(artifact_path.stat().st_mode):
            raise ValueError(f"{key} backup artifact is not a regular file")
        if sha256(artifact_path) != expected_digest:
            raise ValueError(f"{key} backup checksum does not match")

    return {"backupCreatedAt": created_at}


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify_backup.py ARCHIVE_DIR UTC_YYYYMMDD", file=sys.stderr)
        return 2
    try:
        result = verify_backup(Path(sys.argv[1]), sys.argv[2])
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"backup verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
