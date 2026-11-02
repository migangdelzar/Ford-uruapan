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

## Reviewer corrections — 2026-11-02

### Changes

- `deployment/ansible/roles/catalog_sync/files/verify_backup.py` now treats a missing manifest `database` field as legacy `ford_service`; an expected `ford` still rejects it. The verifier can also bind validation to the exact requested PostgreSQL archive named by its timestamped manifest.
- `backup:restore` and `restore-database.sh.j2` are production rollback only. Both require the exact approval ID, target `emme-2`, archive, and selected `ford_service` or `ford` database; the remote template retains the production Docker Compose `pg_restore` operation.
- Added local-only `scripts/backup/restore-rehearsal.sh` and `ford-ai-service/.mise/tasks/backup/restore-isolated`. Rehearsal requires explicit local Unix-socket `DOCKER_HOST`, rejects emme/SSH contexts, checks the `rehearsal-` name and `ford.rehearsal=isolated` label, requires a `rehearsal_` target, verifies the source manifest/database/checksums and exact archive identity, then uses `docker exec` only.
- Expanded backup tests for legacy compatibility, exact archive identity, production gate refusal, and local-only rehearsal requirements. Updated `docs/operations/database-backups.md`.

### Fresh verification

- TDD RED — `python3 -m unittest discover deployment/ansible/roles/backup/tests -v`: initially failed on historical identity fallback, missing isolated files/production gating; the added exact-archive test then failed because the verifier API did not yet support archive binding. All failures were expected; implementations/tests were completed afterward.
- PASS — `python3 -m unittest discover deployment/ansible/roles/backup/tests -v`: **11 tests passed**.
- Real synthetic isolated restore — started only `colima-native`; confirmed `docker ps -a` empty before creating `rehearsal-task5-restore-20261102` (`ford.rehearsal=isolated`). Created synthetic `ford_service` schema/table and row, generated timestamped `pg_dump` gzip plus a synthetic Redis gzip and checksummed manifest, created `rehearsal_task5`, then ran `scripts/backup/restore-rehearsal.sh` with `DOCKER_HOST=unix://$HOME/.colima/native/docker.sock`.
  - Result: `isolated_restore=completed source=ford_service target=rehearsal_task5 archive=ford_service_20261102T160000Z.dump.gz`.
  - Source/target schema-data hash both `1d170161c5511b1185b1e864ff9118ec`; target query returned `rehearsal_task5:1`.
  - Removed only `rehearsal-task5-restore-20261102`; confirmed `native_containers_after_cleanup=none`; stopped `colima-native`.
  - An initial attempt was blocked before restore because the script lacked executable mode; its cleanup removed only that exact temporary container, observed no remaining containers, and stopped `native`. Successful run followed after setting executable mode.
- PASS — `python3 -m unittest discover ford-ai-service/scripts/tests -p 'test_catalog_backup_verifier.py' -v`: **5 tests passed**.
- PASS — `bash -n deployment/ansible/roles/backup/templates/backup-databases.sh.j2 deployment/ansible/roles/backup/templates/restore-database.sh.j2 ford-ai-service/.mise/tasks/backup/restore ford-ai-service/.mise/tasks/backup/restore-isolated scripts/backup/restore-rehearsal.sh`.
- PASS — `git diff --check`.
- No production restore, remote command, SSH, OCI access, real archive, `colima-testbox`, or emme profile was used.

### Commit

- Fresh reviewer-fix implementation commit SHA: `3294b2e8d3894ba8caae7c5a08441b9c64673405` (`Fix Task 5 backup restore reviewer findings`). The report's final verification update is committed separately.
