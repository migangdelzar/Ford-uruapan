# Ford Database Cutover and Release Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Readiness gate:** This plan is subordinate to `docs/operations/production-rollout-readiness.md`. Do not perform a production write until all gates 0–8, target-specific backup/restore proof, and protected approvals pass. Production tools remain off through migration and verification; gate 9 is the only production activation point.

**Goal:** Move every environment to one `ford` application database with `ford_metadata` and `ford_catalog`, verify it, then release Service and Web through existing protected gates.

**Architecture:** Back up and prove restore before each target write. Build/migrate database `ford`; keep chat/lead/user requirements in `ford_metadata` and canonical Ford models/specs/prices/offers/promotions in `ford_catalog`. Validate reader/writer ACLs before switching Service and scraper connections. Keep built-in `public` present and empty after a no-cascade dependency audit. Keep only an external backup for rollback; retire `ford_service` after acceptance.

**Tech Stack:** PostgreSQL/pg_dump/pg_restore, Liquibase, GitHub Actions, OCI Vault, existing OCI blue/green deployment actions, Python/shell preflight tests.

## Global Constraints

- Final application database name is `ford`; final application schemas are exactly `ford_metadata` and `ford_catalog`.
- PostgreSQL system schemas remain; built-in `public` stays present and empty of application/extension objects. Never use `DROP ... CASCADE` or drop `public`.
- No `ford_service` database remains after acceptance. Keep a verified external backup and restore proof, not a second post-cutover DB.
- Production receives the approved candidate snapshot with known gaps; do not call it production-validated.
- Liquibase is the only DDL owner; Service/current database user runs it and gains catalog write access; reader remains SELECT-only.
- `FORD_CATALOG_TOOLS_ENABLED` remains fail-closed (`false`) when unset. Local fixture diagnostics may explicitly enable tools only in a disposable test configuration. Production configuration explicitly sets it `false` through migration and verification; only protected gate 9 may explicitly set it `true`. Keep explicit `false` as the emergency disable switch.
- Use existing release gates and approvals; do not create `emme-test`, bypass a gate, alter DNS/Cloudflare/routing, or deploy scraper customer actions.
- Preserve existing local modifications and unrelated files.

## File Map

- Modify after contract tests: `deployment/preflight` and database runtime templates to validate DB `ford`, schemas, reader user, and feature flag.
- Modify after tests: `.github/workflows/deployment-preflight.yml` to check the new target name without printing secrets.
- Read/dispatch: `.github/workflows/release.yml`, reusable deployment workflows, and approved Web release workflow; do not weaken protection.
- Modify/test only after Liquibase parity: Ford-scraping `.github/workflows/ci.yml`, its deployment workflow, and `tests/unit/deployment-contract.test.ts` so deploy no longer runs its custom DDL migration command.
- Update after each approved cutover: `docs/operations/credential-storage.md`, `docs/operations/production-rollout-readiness.md`, and catalog read-only operations docs with sanitized run IDs/hashes only.
- External backup and catalog snapshot artifacts remain outside Git and contain no passwords.

## Ordered cutover gates (0–9)

Complete gates in order. Missing evidence is blocked, never inferred as passed. Gate 0 is optional local diagnostic work and cannot clear any production-data gate.

### Gate 0 — Disposable fixture diagnostics (Service/QA owner; optional early work)

- [ ] Use a disposable local PostgreSQL instance, reviewed test fixtures, and test-only Service configuration with `FORD_CATALOG_TOOLS_ENABLED=true` explicitly set. Exercise datasource startup, tool registration, approved-view reads, denied writes/private reads, exact/no-match/citation behavior, and unavailable-catalog handling.
- [ ] Record results as **local fixture diagnostics only**. Do not treat the mismatched archive as a verified baseline or touch production/shared databases. Repeat only after a full backup is independently supplied and verified.

**Pass evidence:** Repeatable local fixture results; no production gate is cleared. Stop on any private-read/write access or unexpected behavior.

### Gate 1 — Source integrity (source/data owner)

- [ ] Supply the canonical JSONL hash-generation procedure and per-relation evidence for both known mismatches. Re-run exporter against the unchanged approved archive.
- [ ] Pass only when identity, relation inventory, counts, every approved content hash, and immutable reviewed snapshot identity match. Preserve archive bytes and expected hashes.

**Stop:** Any mismatch or missing source evidence; do not hand-edit hashes or change exporter contract by guess.

### Gate 2 — Full backup and isolated restore (DBA/operator)

- [ ] Create an approved, externally retained full `ford_service` backup per target; hash and manifest it, then restore to an isolated production-like PostgreSQL instance.
- [ ] Independently verify application data, schemas, roles/grants, and both Liquibase histories. The approved catalog-only archive is not this backup.

**Pass evidence:** Verified full backup/restore proof. No target write before proof. Stop if incomplete or restore fails.

### Gate 3 — Duplicate-data authority and ledger provenance (data owner + DBA)

- [ ] On the isolated restore, compare same-named relations row-for-row across `ford`/`public`; inventory owners, counts, constraints, dependencies, and changesets/checksums in both ledgers.
- [ ] Obtain written data-owner decision and reviewed lossless mapping for any differing data; establish one authoritative migration-history ledger.

**Stop:** Any unresolved discrepancy, unknown owner, checksum conflict, or absent owner decision. Never choose a winner or merge by assumption.

### Gate 4 — Migration and extension review (DBA + application/migration owner)

- [ ] Reconcile changeset 025 with observed `ford.ai_execution` and other duplicate relations. Reconcile changeset 026 with `pgcrypto` in `public`, extension ownership/version/dependencies, and supported relocation.
- [ ] Define and review a versioned correction or bootstrap path against the restored baseline and active app version; do not edit already-applied changesets or weaken guards.

**Stop:** Incompatibility, unsupported relocation, unknown object, or any need for `CASCADE`/dropping built-in `public`.

### Gate 5 — Least privilege and schema contract (DBA/security owner)

- [ ] Replace the superuser application role with reviewed migration/runtime identities. Audit effective `ford_catalog_reader` grants and its secret-specific Vault identity/access without printing secret values.
- [ ] Prove reader can select approved catalog views only, cannot write or access metadata/private capture data, and `public` remains present and empty of application/extension objects.

**Pass evidence:** Reviewed grants and secret path; least-privilege allow/deny probes. Stop on excess permissions or unknown secret consumer.

### Gate 6 — Isolated migration rehearsal (migration owner + QA)

- [ ] Apply exact reviewed changesets and approved snapshot only to the isolated restore. Verify application-data preservation, one authoritative ledger, extension placement, schema inventory, counts/hashes, reader allow/deny probes, and old/candidate app compatibility.
- [ ] Record repeatable commands and sanitized results.

**Stop:** Unknown object/checksum, data loss, permission error, non-repeatable result, or `CASCADE` requirement.

### Gate 7 — Protected release readiness (release owner + platform/security owners)

- [ ] Verify full read-only target/deployment inventory, host disk/capacity (including emme-2 headroom), runtime Vault identity/secret access, CI, reviewed source SHA, immutable image digest/attestation, protected environments/reviewers, rollback image, and tested backup/restore procedure.
- [ ] Refresh stale repository/workflow status immediately before release; obtain and record every required protected approval.

**Stop:** Any missing inventory, capacity, Vault, CI, artifact, protection, approval, or rollback evidence.

### Gate 8 — Protected sequential rollout, tools off (authorized release + migration owners)

- [ ] Promote sequentially via approved environment path. Take and verify a fresh backup for each target, obtain its approval, and retain per-target rollback evidence.
- [ ] Keep `FORD_CATALOG_TOOLS_ENABLED=false` during database and Service migration/verification. Confirm approved artifact, datasource, health, schema/data/ACL checks, and rollback artifacts on both hosts before advancing.

**Stop:** Any failed health/data/ACL check, target drift, missing backup/approval, or unavailable rollback artifact. Do not change DNS, Cloudflare, routing, or scraper customer actions.

### Gate 9 — Explicit protected production default-on (release owner; final gate)

- [ ] Only after gates 1–8 and all approvals pass, explicitly set `FORD_CATALOG_TOOLS_ENABLED=true` in protected production configuration as desired state. Never change the application fallback from false; explicit `false` remains emergency disable.
- [ ] Run and record synthetic exact-match, no-match, citation, and unavailable-catalog checks and approval/observation. If any activation check fails, set protected flag false and follow approved application rollback plus verified external database restore; do not automatically reverse Liquibase.

**Pass evidence:** Explicit protected configuration, successful recorded synthetic checks, and approval. Until then production remains off.

## Stop Conditions

- Source SHA or data manifest is missing/changed, or the local Colima source cannot be recovered safely.
- Backup/restore proof, target identity, Liquibase parity, extension plan, disk space, health, role ACL, or rollback artifact is missing.
- Any operation needs `CASCADE`, exposes `ford_metadata` to the reader, grants reader writes, or changes Service data.
- Existing production approvals are missing or someone asks to bypass them.
- Any DNS, Cloudflare, public route, or port policy change is needed.
- A gate's required owner evidence or protected approval is absent. Local fixture diagnostics do not override a blocked production gate.
