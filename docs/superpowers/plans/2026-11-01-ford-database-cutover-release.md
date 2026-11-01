# Ford Database Cutover and Release Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every environment to one `ford` application database with `ford_metadata` and `ford_catalog`, verify it, then release Service and Web through existing protected gates.

**Architecture:** Back up and prove restore before each target write. Build/migrate database `ford`, move existing Service rows to `ford_metadata`, let Liquibase create/load `ford_catalog`, validate reader/writer ACLs, and only then switch Service and scraper connections. Remove `public` only after a no-cascade dependency audit. Keep only an external backup for rollback; retire `ford_service` after the new database is accepted.

**Tech Stack:** PostgreSQL/pg_dump/pg_restore, Liquibase, GitHub Actions, OCI Vault, existing OCI blue/green deployment actions, Python/shell preflight tests.

## Global Constraints

- Final application database name is `ford`; final application schemas are exactly `ford_metadata` and `ford_catalog`.
- PostgreSQL system schemas remain; `public` must be empty and removed only after full dependency/extension review, without `CASCADE`.
- No `ford_service` database remains after acceptance. Keep a verified external backup and restore proof, not a second post-cutover DB.
- Production receives the approved candidate snapshot with known gaps; do not call it production-validated.
- Liquibase is the only DDL owner; Service/current database user runs it and gains catalog write access; reader remains SELECT-only.
- Keep catalog tools disabled until all checks pass. Use existing release gates and approvals; do not create `emme-test`, bypass a gate, alter DNS/Cloudflare/routing, or deploy scraper customer actions.
- Preserve existing local modifications and unrelated files.

## File Map

- Modify after contract tests: `deployment/preflight` and database runtime templates to validate DB `ford`, schemas, reader user, and feature flag.
- Modify after tests: `.github/workflows/deployment-preflight.yml` to check the new target name without printing secrets.
- Read/dispatch: `.github/workflows/release.yml`, reusable deployment workflows, and approved Web release workflow; do not weaken protection.
- Modify/test only after Liquibase parity: Ford-scraping `.github/workflows/ci.yml`, its deployment workflow, and `tests/unit/deployment-contract.test.ts` so deploy no longer runs its custom DDL migration command.
- Update after each approved cutover: `docs/operations/credential-storage.md`, `docs/operations/production-rollout-readiness.md`, and catalog read-only operations docs with sanitized run IDs/hashes only.
- External backup and catalog snapshot artifacts remain outside Git and contain no passwords.

## Task 1: Add fail-closed database identity preflight

- [ ] Add contract tests asserting database name is exactly `ford`, required schemas are `ford_metadata`/`ford_catalog`, no application relations remain in `public`, and feature flag is false.
- [ ] Run focused workflow/preflight tests; expected: fail on current `ford_service` checks.
- [ ] Update runtime examples and preflight checks to validate the one-database contract without printing credential values. Keep all production approvals and release conditions unchanged.
- [ ] Run Python contract tests and shell tests; expected: all target-identity tests pass.

**Verification:** Wrong DB/schema, reader identity, or flag fails before any migration/deploy command.

## Task 2: Prepare each environment from verified backup

- [ ] Inspect database identity, schemas, table owners, extension versions, schema migrations, roles/grants, `search_path`, active sessions, and disk space using read-only queries.
- [ ] Create a fresh external database backup and manifest. Verify SHA-256 and restore it into a disposable isolated PostgreSQL instance. Never print connection secrets.
- [ ] Stop if `public` has unexpected objects, extension relocation is unsupported, data mapping is ambiguous, or backup restore proof fails.
- [ ] Verify that the new catalog SQL artifact SHA and source manifest are approved and match the immutable Liquibase changeset name.

**Verification:** Backups restore; exact pre-cutover row counts and rollback commands are recorded; no target write yet.

## Task 3: Migrate one test environment first

- [ ] Create or restore database `ford` from the verified backup without deleting the source during migration. Apply Liquibase changesets and verify `ford_metadata` Service rows and `ford_catalog` snapshot rows.
- [ ] Relocate supported extensions and validate dependencies. Attempt `DROP SCHEMA public` without `CASCADE`; if it fails, stop and inspect rather than force it.
- [ ] Verify only the two application schemas remain, Liquibase history is in `ford_metadata`, scraper writes target `ford_catalog`, and reader SELECT/write-denial probes pass.
- [ ] Configure emme Service and scraper connection URLs to database `ford`, with schema-specific identities. Keep tools false and run health checks.
- [ ] After acceptance, retire the old `ford_service` database as authorized; keep the external backup only.

**Verification:** Service data counts match backup; catalog matches manifest; app health is good; no reader write/private access; no public schema.

## Task 4: Release Service and Web through existing approvals

- [ ] Revalidate latest annotated tags, exact source SHAs, CI, image/artifact digests, attestations, and rollback artifact immediately before deploy.
- [ ] Deploy Service through existing protected workflow with catalog tools off; verify datasource target, Liquibase status, health, and rollback digest.
- [ ] Deploy Web only after Service passes; verify root, login, dashboard, fingerprinted asset, and API health. Make no routing change.
- [ ] Enable catalog tools only after Vault config, reader ACL, health, and synthetic catalog probes pass. If any fails, switch tools off and restore the verified external DB backup and previous image digests.
- [ ] Record sanitized workflow IDs, artifact hashes, DB backup hash, catalog manifest hash, feature flag, and test outputs.

**Verification:** Existing release protections remain intact; both services run verified immutable artifacts; no route or customer-data change occurs.

## Task 5: Promote sequentially through remaining environments

- [ ] Repeat Tasks 2–4 for each remaining environment, including production, one environment at a time. Require fresh backup/restore proof and existing protected approval for each.
- [ ] Before production apply, display and explicitly review the candidate SHA and known coverage/price limitations. Stop if source SHA changed from the reviewed manifest.
- [ ] Keep catalog tools disabled until production Service health, reader permissions, and synthetic checks pass.
- [ ] Retire each old `ford_service` database only after that environment's acceptance; retain external backup through the approved rollback window.

**Verification:** Every environment reports one app DB `ford`, two app schemas, `public` absent, correct data counts, reader-only tool access, and a verified rollback backup.

## Stop Conditions

- Source SHA or data manifest is missing/changed, or the local Colima source cannot be recovered safely.
- Backup/restore proof, target identity, Liquibase parity, extension plan, disk space, health, role ACL, or rollback artifact is missing.
- Any operation needs `CASCADE`, exposes `ford_metadata` to the reader, grants reader writes, or changes Service data.
- Existing production approvals are missing or someone asks to bypass them.
- Any DNS, Cloudflare, public route, or port policy change is needed.
