#!/usr/bin/env python3
"""Verify the newest local backup manifest and both referenced artifacts."""

import hashlib
import json
import re
import stat
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_backup(
    archive: Path, utc_day: str, expected_database: str = "ford_service",
    expected_archive: Optional[str] = None,
) -> dict[str, str]:
    if expected_database not in {"ford_service", "ford"}:
        raise ValueError("expected database must be ford_service or ford")
    if not re.fullmatch(r"[0-9]{8}", utc_day):
        raise ValueError("expected UTC date must be YYYYMMDD")
    if expected_archive is not None:
        archive_match = re.fullmatch(
            r"[A-Za-z0-9._-]+_([0-9]{8}T[0-9]{6}Z)\.dump\.gz", expected_archive
        )
        if not archive_match or archive_match.group(1)[:8] != utc_day:
            raise ValueError("requested PostgreSQL archive name is invalid")
        manifests = [archive / f"manifest_{archive_match.group(1)}.json"]
    else:
        manifests = sorted(archive.glob(f"manifest_{utc_day}T*.json"))
    if not manifests:
        raise ValueError("same-day backup manifest is missing")

    manifest_path = manifests[-1]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("backup manifest is malformed")
    if expected_archive is not None and manifest.get("postgres") != expected_archive:
        raise ValueError("manifest PostgreSQL archive does not match requested archive")
    # Manifests written before database identity was introduced are legacy
    # ford_service backups. Never infer the replacement database from absence.
    database = manifest.get("database", "ford_service")
    if database not in {"ford_service", "ford"}:
        raise ValueError("backup manifest database is missing or invalid")
    if database != expected_database:
        raise ValueError(f"backup manifest database {database!r} does not match expected database")
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
    if len(sys.argv) not in {3, 4, 5}:
        print("usage: verify_backup.py ARCHIVE_DIR UTC_YYYYMMDD [EXPECTED_DATABASE [EXPECTED_ARCHIVE]]", file=sys.stderr)
        return 2
    try:
        result = verify_backup(
            Path(sys.argv[1]), sys.argv[2], sys.argv[3] if len(sys.argv) >= 4 else "ford_service",
            sys.argv[4] if len(sys.argv) == 5 else None,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"backup verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
