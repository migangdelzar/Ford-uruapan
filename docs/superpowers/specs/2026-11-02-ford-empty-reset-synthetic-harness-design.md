# Ford Empty-Reset Synthetic Harness Design

**Date:** 2026-11-02
**Status:** Approved for local implementation; not production acceptance evidence.

## Goal and boundary

Exercise the actual guarded Ford empty-reset and exact-directory rollback Ansible playbooks with synthetic files in a disposable Linux container. Prove that the reset moves old PostgreSQL/Redis sentinel files aside, creates empty active directories, and that rollback preserves replacement files while restoring old files byte-for-byte.

This is a safety regression harness only. It does not restore a production backup, validate an approved image, prove production grants/health, or satisfy Task 7 release rehearsal. It must never target emme hosts or access production credentials, backup objects, or databases.

## Architecture

- A local test runner creates a temporary fixture containing fake Compose files, a canonical `data_volume_mount`, synthetic PostgreSQL and Redis directories, and a valid synthetic backup manifest plus two checksum-matched artifact files.
- The runner launches a disposable Linux container as root with Ansible installed. It mounts the repository read-only and the fixture at a dedicated temporary path. It does **not** mount any Docker socket, credentials, home directory, or production config.
- The container invokes the real reset then rollback playbooks using a localhost inventory aliased as `emme-2` in the `data` group, explicit synthetic approval values, and fixture-only paths.
- A fixture-provided fake `docker` executable records arguments and accepts only the expected `data` Compose project stop for `postgres redis`. It cannot contact a daemon.
- The runner checks reset state, exact archive paths, sentinel bytes, empty active directories, rollback state, restored sentinel bytes, preserved replacement directories, and expected fake-Docker calls. `finally` cleanup removes the container and temporary fixture.

## Failure coverage

The harness must also prove fail-closed behavior for at least: missing approval, wrong inventory alias/group, wrong manifest digest, modified/missing backup artifact, pre-existing reset archive destination, mismatched rollback approval/digest, symlinked protected path, and occupied rollback preservation destination. Each refusal must occur before the corresponding destructive move. After each failed fixture, compare sentinel hashes and ensure no unexpected path was created.

## Safety and operational controls

- Test-only approval IDs and manifest data are generated per run and never accepted from production deployment config.
- Ansible inventory is generated in the temporary directory and contains only localhost connection settings.
- The container runner must reject any configured target other than its generated localhost alias and must not inherit SSH, OCI, Vault, or Docker connection variables.
- Container image and package installation are test dependencies only; no image is pushed or deployed. Pin the harness base image to an immutable digest when a reviewed local test image is selected; otherwise fail with a clear prerequisite message rather than silently using `latest`.
- Documentation and output label results `SYNTHETIC PLAYBOOK SAFETY TEST`; they must not say “replacement rehearsal passed” or “production ready.”

## Verification

- Test the harness first against the actual playbooks and require expected reset/rollback state transitions.
- Mutate each approval/digest/path precondition in an isolated fixture and require a nonzero Ansible result with original sentinel hashes unchanged.
- Confirm the fake Docker command is the only Docker executable available inside the container and that no Docker socket is mounted.
- Confirm cleanup after both passing and deliberately failing cases; run Node/Python contracts and Ansible syntax checks afterward.

## Deferred work

The protected Task 7 rehearsal remains blocked until reviewed immutable Postgres, Redis, and Service artifacts, the signed DBA role/schema contract, and separate authorization for an access-controlled verified-backup clone are available. The synthetic harness cannot replace those inputs.
