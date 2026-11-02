# Task 5 Report — Backup/Restore Evidence

## Changes

- Backup manifests now identify `ford_service` by default, or `ford` when explicitly selected, and include SHA-256 checksums for PostgreSQL and Redis archives.
- The verifier validates manifest structure, approved/expected database identity, timestamp identity, archive pair, checksum format, and archive hashes. Existing default behavior remains legacy `ford_service` with timestamp-only verifier output.
- Rehearsal restore now requires a `rehearsal-` container and a non-production database name, and uses `docker exec` rather than production Compose. Mise wrapper requires both target names. Production rollback was not invoked.
- Local seven-day retention and OCI bucket lifecycle behavior unchanged.

## TDD and verification evidence

- RED command: `python3 -m unittest discover deployment/ansible/roles/backup/tests -v`
  - Result before implementation: 3 expected failures for absent identity validation/return expectations (the newly added tests ran against the existing verifier); missing identity and unapproved identity were accepted, and candidate verifier API was unavailable.
- GREEN command: `python3 -m unittest discover deployment/ansible/roles/backup/tests -v`
  - Result: **7 tests passed**.
- Compatibility command: `python3 -m unittest discover ford-ai-service/scripts/tests -p 'test_catalog_backup_verifier.py' -v`
  - Result: **5 tests passed**.
- Script syntax: `bash -n deployment/ansible/roles/backup/templates/backup-databases.sh.j2 deployment/ansible/roles/backup/templates/restore-database.sh.j2 ford-ai-service/.mise/tasks/backup/restore`
  - Result: exit 0.
- Diff hygiene: `git diff --check`
  - Result: exit 0.
- No backup/restore execution, OCI access, Docker restore, or remote-host operation performed. No disposable restore test performed because live restore operations are explicitly prohibited in this task.

## Commit

- Implementation commit: `54d39b4` (`Harden backup manifest identity and isolated restore`).
