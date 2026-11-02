# Monthly Catalog Sync Workflow Design

**Status:** Design decisions approved in conversation; awaiting written-spec review
**Date:** 2026-10-17

## Goal

Run Ford-scraping's catalog synchronization automatically once a month from a protected GitHub Actions workflow, while keeping catalog writes on the existing Ford-scraping TypeScript implementation and PostgreSQL host network.

## Decisions

- GitHub Actions YAML owns the monthly trigger and workflow result.
- The workflow runs on the first day of each month at 12:17 p.m. `America/Mexico_City` (18:17 UTC; GitHub cron `17 18 1 * *`), plus a manual `workflow_dispatch` trigger. The time is a target because GitHub may delay scheduled runs.
- A protected GitHub Actions job invokes Ansible over SSH to emme-2.
- Ansible launches a one-shot container from the currently released immutable Ford-scraping image. The container runs `node dist/src/cli.js sync`, then exits.
- The one-shot sync uses the existing private Docker data network and host-managed database configuration. GitHub Actions does not connect directly to PostgreSQL and does not receive the database password.
- The monthly run performs the existing full sync: crawl `/Todos/`, `/Seminuevos/`, `/Promociones/`, and `/Financiamiento/`; ingest changed pages; refresh used-vehicle and financing records; and process pending embeddings (up to the current configured limit of 100).
- The crawl still checks source pages for changes. Unchanged pages are not re-ingested. Used-vehicle and financing synchronization and embedding work retain the current full-sync behavior.
- Before the sync, Ansible verifies that the existing monthly PostgreSQL/Redis backup completed and verified its artifacts on the same UTC calendar date. If that check fails, the workflow fails without starting the sync.
- A nonzero `pagesFailed` or `embeddingFailures` count makes the workflow fail, even if other parts of the sync completed. The run summary reports the sync counters without customer or credential data.
- GitHub Actions reports failures through the repository's normal Actions notifications and records the run summary. No additional notification integration is added.
- Workflow concurrency and the existing database sync lock prevent overlapping catalog runs.
- A repository variable gates scheduled invocations during cutover. Manual dispatch can validate the new workflow while the old scheduler remains active; enable scheduled invocations only after the old trigger is retired through its protected release path.
- The schedule replaces the long-running Node cron scheduler as the trigger. A protected reversible `disable` release operation removes the old container and records its immutable digest; rollback restores it. MCP releases do not start or restore the scheduler. The scraper image and repository remain; only the MCP runtime is retired in the wider cutover.

## Runtime Flow

1. GitHub Actions starts the scheduled workflow from the default branch, or an operator starts the manual workflow.
2. A protected job checks out the repository's Ansible playbook and runs a backup-verification playbook on emme-2.
3. Ansible requires a successful backup from that UTC day. A missing or invalid backup stops the job before any catalog write.
4. Ansible reads the currently promoted immutable scraper image reference from host-managed release state and runs the one-shot sync container on the private data network.
5. The TypeScript sync records counters in the existing sync-run store and writes its aggregate result for Ansible to return to GitHub Actions.
6. The workflow fails if the command errors, if `pagesFailed > 0`, or if `embeddingFailures > 0`; otherwise it succeeds and publishes a bounded summary.

The scheduled job does not run migrations, build or publish an image, deploy Ford Service, or access the database from the hosted runner. Image promotion remains a separate protected release operation.

## Implementation Boundaries

- Ford-scraping remains the only owner of catalog crawling, ingestion, migrations, scheduler/sync behavior, and catalog writes.
- Ford Service remains a read-only consumer of the versioned catalog views; scheduling does not complete Java/Node tool-output parity.
- No crawler logic or synchronization behavior is reimplemented in Rust, Python, or Java.
- Existing published image and release state must identify one immutable digest before scheduled runs are enabled.
- The current long-running `node dist/src/cli.js scheduler` service must not remain an active trigger alongside GitHub Actions. Cutover must disable it after the manual workflow succeeds, with rollback able to restore the previous trigger and image. MCP deployment must not silently recreate the trigger.
- Enabling this scheduled sync requires its own protected workflow, backup, image, and rollback checks. It does not wait on Java tool parity. The retired Node MCP production footprint remains until the protected Service-owned tool configuration and cutover checks pass.

## Failure and Recovery

- Backup validation failure: skip sync and fail the workflow with the missing/invalid backup date in the diagnostic.
- Sync command error: preserve the sync-run failure record, return a nonzero status, and expose only bounded counters/error category in GitHub output.
- Partial sync (`pagesFailed > 0` or `embeddingFailures > 0`): mark the workflow failed and report counts. Already committed source-backed records remain; a manual retry is allowed after backup validation.
- Concurrent scheduled/manual run: serialize through Actions concurrency and reject/serialize at the PostgreSQL sync lock.
- Manual retry: use the same pinned image and backup gate; never build a different image during a recovery run.
- Rollback: restore the prior immutable scraper image and the prior scheduler trigger through the protected release workflow. Do not remove catalog data or migrations.

## Verification Requirements

- Unit-test the TypeScript sync command's exit status for successful, partial, and thrown-error results.
- Test that unchanged crawled pages are skipped for ingestion while financing/used inventory/embedding work retains the existing full-sync behavior.
- Add Ansible contract tests for backup-date validation, pinned image selection, one-shot command, private-network use, no migrations, failure propagation, and old-scheduler cutover/rollback.
- Add workflow contract checks for monthly UTC cron, manual dispatch, production environment/branch restrictions, minimal permissions, concurrency, summary output, and no direct database credentials.
- Exercise the Ansible runner against a disposable PostgreSQL database and a test backup marker; cover missing/failed backup and partial sync cases.
- Do not enable the production workflow until immutable image promotion, the same-day backup check, the one-shot command, rollback, and Service-owned catalog configuration/release gates are verified. Node/MCP parity is not required.

## Alternatives Considered

1. **GitHub Actions schedule + Ansible one-shot sync (selected):** keeps the schedule and run history in CI, reuses the TypeScript writer, and keeps database access on emme-2.
2. **Ansible-managed systemd timer:** viable and close to the host, but stores scheduling state outside the selected GitHub workflow and its run history.
3. **Long-running scheduler container:** already deployed and low-change, but retains a scheduler process in the scraper deployment and duplicates the requested GitHub-owned schedule.

## Open Operational Checks

- Confirm the currently released scraper image digest is recorded in host release state and remains pullable by the production host.
- Confirm backup manifest/object verification can reliably establish a same-day successful backup for the scheduled run.
- Confirm workflow environment settings permit unattended monthly execution while restricting secrets to the protected default branch.
- Confirm alert recipients have GitHub Actions failure notifications enabled.
