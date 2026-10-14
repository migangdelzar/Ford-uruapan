# Database Backups

emme-2 runs the database backup as a systemd oneshot service. Ansible installs
and enables `ford-backup.timer`, which starts `ford-backup.service` daily at
03:15 UTC with a 15-minute randomized delay. The service archives PostgreSQL
and Redis, uploads both archives and a checksum manifest to OCI Object Storage,
and confirms each uploaded object exists before reporting success. Local copies
are retained for seven days; Object Storage retention is controlled by the
bucket lifecycle policy.

## GitHub Actions controls

The `Database Backup Operations` workflow provides two operator actions:

- **Run** starts the existing `ford-backup.service` on emme-2, then requires a
  successful systemd result and a newly created manifest.
- **Verify** checks that `ford-backup.timer` is enabled and active, and that the
  newest backup manifest is no older than 36 hours.

The workflow also runs **Verify** daily at 04:30 UTC. This checks freshness
after the VM timer's normal run window; it does not start another scheduled
backup. A failed check fails the Actions run so GitHub can notify repository
maintainers.

Both actions use the `deployment-preflight` environment's pinned emme-2 SSH
key, known-hosts data, host variable, and SSH user variable. They run only from
`main`, use strict host-key checking, and do not upload database contents as
GitHub artifacts. The VM timer remains the sole automatic backup scheduler.

## Manual operation

In **Actions → Database Backup Operations → Run workflow**, select `run` to
create a backup now or `verify` to inspect timer and backup freshness without
creating a backup. Successful runs print only the manifest timestamp; the
database archives remain on the VM and in OCI Object Storage.

The backup's presence and upload are not a restore test. Use the existing
restore procedure and a separate database before relying on a backup for
recovery; never test a restore over the live database.
