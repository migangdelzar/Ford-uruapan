# OCI Database Backup Design

## Current state

The existing Ansible backup role installs a root cron job on `emme-2` that runs
at 03:15 UTC, creates a local PostgreSQL custom-format dump, compresses it, and
deletes local dumps older than seven days. It does not upload to Object Storage,
does not encrypt archives, does not snapshot Redis, and does not verify a remote
backup object. This is insufficient for VM replacement or data-volume failure.

## Target state

`emme-2` runs a systemd service and timer. The service:

1. verifies PostgreSQL and Redis are healthy;
2. creates a timestamped PostgreSQL custom dump;
3. requests a Redis RDB snapshot because Redis is rebuildable cache state;
4. compresses the PostgreSQL and Redis artifacts and creates a manifest with
   timestamps and SHA-256 hashes;
5. uploads the archives and manifest to the private `emme-business` bucket;
6. verifies each uploaded object exists through Object Storage before local pruning;
7. removes local archives beyond the seven-day local retention window; and
8. writes a success marker used by destructive infrastructure guardrails.

The first implementation uses an OCI instance principal on `emme-2`; no API
private key or Object Storage secret is copied to the VM. OCI-managed
server-side encryption protects the private bucket. The dynamic group policy
grants only object create, read, inspect, and delete access to the
`ford/backups/*` prefix. Terraform owns the bucket, lifecycle rule, dynamic
group, and IAM policies but never runs database commands.

## Retention and recovery

- Object Storage keeps fourteen daily archives under `ford/backups/` through a
  lifecycle rule. Local storage keeps seven days for fast recovery.
- Redis is included for operational continuity but is not the source of truth;
  PostgreSQL is the authoritative backup.
- `mise run backup:run` triggers the same remote service manually.
- `mise run backup:list` lists bucket objects without displaying credentials.
- `mise run backup:verify` finds the newest remote manifest and verifies it with
  an Object Storage HEAD request.
- `mise run backup:restore` requires an explicit confirmation token, a fresh
  verified backup, and application quiescence before replacing database data.

## Failure policy

The timer fails closed if the database dump, upload, or remote
verification fails. It never deletes the only local archive after a failed
upload. It emits a concise status line to journald and does not log database
contents, credentials, customer data, prompt text, or object-storage tokens.
