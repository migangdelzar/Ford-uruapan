# Ford Liquibase Schema and Catalog Data Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Ford Service Liquibase the single DDL owner for the `ford` database and load a verified Ford-scraping catalog snapshot into `ford_catalog` through immutable Liquibase changesets.

**Architecture:** Keep chat/Meta sessions, leads, and user requirements in `ford_metadata`; keep canonical Ford models/specs/prices/offers/promotions in `ford_catalog`. Liquibase changelogs in Ford Service create/manage both schemas and load catalog data; Ford-scraping stops running DDL only after schema parity and writer tests pass.

**Tech Stack:** Java/Spring Boot, Liquibase YAML/SQL, PostgreSQL 16 + pgvector, Python 3 `unittest`, Node.js 22+, TypeScript, Vitest, Docker/Colima.

## Global Constraints

- Application database name is `ford` in every environment.
- The only application schemas are `ford_metadata` and `ford_catalog`; PostgreSQL system schemas remain.
- Keep built-in `public` present and empty of application/extension objects; never drop it or use `CASCADE`.
- Bot/Meta sessions, leads, user requirements, and tenant-scoped knowledge documents/embeddings remain in `ford_metadata`; canonical Ford models/specs/prices/offers/promotions and source-owned Ford catalog documents/embeddings remain in `ford_catalog`.
- Ford Service Liquibase is the only DDL migration owner; Ford-scraping writes data and does not run its SQL migrations after cutover.
- Use the current Service database user for Liquibase as explicitly selected; this user gains catalog DDL/DML rights. `ford_catalog_reader` stays read-only and cannot see capture/provenance relations. The approved source currently grants it SELECT on `ford_read.document_provenance_v1`; Liquibase must remove that grant and tests must prove denial.
- Every environment, including production, receives the approved candidate snapshot. Its known coverage and price gaps must be stated; do not claim production validation.
- Applied Liquibase changesets are immutable. Every refreshed snapshot gets a new SHA-identified changeset.
- No full database restore over the target. Back up outside PostgreSQL and prove restore before writes.
- Preserve all unrelated dirty files in the active `Ford-uruapan` checkout and separate dirty Ford-scraping checkout.

## File Map

- Create `ford-ai-service/scripts/catalog/export-liquibase-snapshot.py` — verify the local source database/archive and generate deterministic Liquibase-ready data SQL plus a source manifest.
- Create `ford-ai-service/scripts/tests/test_export_liquibase_snapshot.py` — test manifest identity, object allowlist, counts, generated SQL determinism, and rejection cases.
- Create `ford-ai-service/src/main/resources/db/changelog/catalog/db.changelog-catalog.yaml` — include immutable catalog schema/data changesets.
- Create catalog Liquibase changesets under `ford-ai-service/src/main/resources/db/changelog/catalog/changes/` — create staging objects, load versioned rows, validate, and cut over.
- Modify `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml` — include the new catalog changelog.
- Modify `ford-ai-service/src/main/resources/application.yml` and deployment templates — set database `ford`, Liquibase metadata/default schema `ford_metadata`, and Service JDBC `currentSchema=ford_metadata`.
- Create/modify Ford-scraping files only in a clean, task-specific checkout: `src/storage/database.ts`, SQL references under `src/storage/`, migration tests, and migration docs. The existing detached checkout has user changes; do not edit it in place.
- Create Service and scraper PostgreSQL integration tests that verify actual schema names, relation grants, and data preservation.

## Task 1: Recover and identify the current source safely

**Files:** None; read-only investigation, then a fresh source artifact only after the database starts.

**Interfaces:** Source database must be `ford_catalog_candidate_20261025` or another explicitly re-reviewed current candidate. Output manifest records source DB, fresh SHA-256, server/extension versions, relation inventory, row counts, content hashes, and generation time.

- [ ] Inspect Colima `testbox` state and its disk-attachment diagnostics. Do not delete lock files, recreate the profile, or alter the VM disk. If the disk remains attached or the source database cannot be proven, stop and ask the operator to recover Colima.
- [ ] Once the source database is reachable, query database identity, schema/relation inventory, extension versions, and row counts using a read-only connection. Do not run scraper migrations or writes.
- [ ] Create an external checksum-verified source dump and manifest. Do not use the different `b0b45e6e...` archive as a substitute. Record the new SHA and counts for review before generating migration SQL.
- [ ] Restore the dump into a disposable local PostgreSQL database and prove the archive restores. Drop only that uniquely named disposable database after the restore test passes.

**Verification:** Source identity and counts are known, archive SHA is `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d`, all 12 table row counts/content hashes match its manifest, disposable restore succeeded, and candidate limitations remain documented. User approved this exact SHA before migration SQL generation. No emme or production database changes occur.

## Task 2: Characterize schema ownership and define tests

**Files:**
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordSchemaOwnershipIntegrationTest.java`
- Create: clean Ford-scraping checkout `tests/integration/catalog-schema-contract.test.ts`
- Read: Ford-scraping `db/migrations/*.sql` and `src/storage/*.ts`

- [ ] Add PostgreSQL integration assertions that Service-owned tables resolve in `ford_metadata`, catalog read relations resolve in `ford_catalog`, Liquibase tracking tables resolve in `ford_metadata`, and no application relations resolve in `public`.
- [ ] Add scraper integration assertions that writer queries can insert/update approved `ford_catalog` objects and cannot write Service-owned `ford_metadata` objects.
- [ ] Run `./gradlew integrationTest --tests '*FordSchemaOwnershipIntegrationTest'` from `ford-ai-service`; expected: fail because target schemas do not exist yet.
- [ ] Run `npm run test:integration -- tests/integration/catalog-schema-contract.test.ts` in the clean Ford-scraping checkout; expected: fail because current queries target `ford.*`.
- [ ] Record the exact scraper table, view, extension, FK, and index inventory. Include source tables and private capture tables; exclude none silently.

**Verification:** Failing tests identify current schema mismatches. Inventory distinguishes Service metadata, scraper writer relations, approved reader relations, and private capture data.

## Task 3: Add Liquibase ownership for schemas and Service metadata

**Files:**
- Create: `ford-ai-service/src/main/resources/db/changelog/changes/025-ford-metadata-schema.yaml`
- Create: `ford-ai-service/src/main/resources/db/changelog/catalog/db.changelog-catalog.yaml`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Create: `ford-ai-service/src/main/java/com/ford/service/database/FordMetadataLiquibaseBootstrap.java` and `FordLiquibaseConfiguration.java` — bootstrap the metadata schema/history before Spring Liquibase.
- Create: `ford-ai-service/src/main/resources/db/bootstrap/001-ford-metadata-liquibase-history.sql` — create `ford_metadata` and safely move existing Liquibase history/lock tables before Liquibase initialization.
- Modify: deployment DB bootstrap/runtime templates that set the DB name and Liquibase schema
- Modify: schema ownership integration tests from Task 2

**Interfaces:** Runtime JDBC connects to database `ford` with `currentSchema=ford_metadata`. A pre-Liquibase SQL initializer runs on every startup, idempotently creates `ford_metadata`, and relocates existing `DATABASECHANGELOG` plus `DATABASECHANGELOGLOCK` from the one verified current schema. It must stop if either table exists in multiple schemas or its location is ambiguous. Liquibase then uses `ford_metadata`; the catalog changelog creates and manages `ford_catalog`.

- [ ] Extend the integration test first: seed representative applied changelog rows and the lock table in the current schema; run the pre-Liquibase initializer; apply the master changelog twice; assert history rows/checksums are unchanged, no prior changeset reruns, and Service rows survive schema moves.
- [ ] Run the focused integration test and verify expected failure against the current unqualified changelog.
- [ ] Add a bootstrap that idempotently creates only `ford_metadata` before Liquibase starts. Configure `spring.liquibase.liquibase-schema=ford_metadata`, `spring.liquibase.default-schema=ford_metadata`, and JDBC `currentSchema=ford_metadata`; keep the database name exactly `ford`.
- [ ] Add forward-only Liquibase changesets that move chat/lead/requirement and tenant-scoped knowledge/document/embedding relations to `ford_metadata`; move only reviewed source-owned canonical Ford model/spec/price/offer/promotion/catalog-document/embedding relations to `ford_catalog`. Preserve legacy catalog values with source provenance/history; the approved snapshot wins current-value conflicts. No trusted stock feed exists, so do not expose `vehicle_unit` as stock truth; route availability questions to human handoff. Use explicit object names; fail on unclassified relations or if a price cannot be distinguished from a user-entered budget/requirement.
- [x] Add the pre-Liquibase bootstrap and PostgreSQL tests for fresh schema creation, idempotence, exact history/lock relocation with row preservation, and ambiguous-location rejection.
- [x] Configure the service database default as `ford`, JDBC `currentSchema=ford_metadata`, and Liquibase metadata/default schema as `ford_metadata`.
- [ ] Register the catalog changelog in the master changelog and set explicit schema names on every catalog changeset. Do not modify checksums or contents of already-applied changesets.
- [ ] Run `./gradlew integrationTest --tests '*FordSchemaOwnershipIntegrationTest'`; expected: both fresh install and upgrade paths pass.

**Verification:** New and upgraded disposable databases place chat/lead/requirement tables and Liquibase metadata in `ford_metadata`; canonical Ford catalog relations live only in `ford_catalog`; user records remain unchanged.

## Task 4: Port scraper DDL and grants into Liquibase

**Files:**
- Create: `ford-ai-service/src/main/resources/db/changelog/catalog/changes/001-ford-catalog-schema.yaml`
- Create: `ford-ai-service/src/main/resources/db/changelog/catalog/changes/002-ford-catalog-roles.yaml`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordSchemaOwnershipIntegrationTest.java`
- Read: all 21 checked-in Ford-scraping SQL migrations and the verified live source inventory

- [ ] Extend the PostgreSQL integration test to assert every approved catalog table, view, FK, index, extension, 1,024-dimensional `bge-m3` document embedding, and keyword search vector exists in `ford_catalog`, while private capture/provenance relations remain in that schema but are not selectable by `ford_catalog_reader`.
- [ ] Run the focused integration test and verify the missing catalog objects/grants fail before implementation.
- [ ] Port the reviewed scraper DDL into explicit Liquibase changesets, preserving data types, indexes, uniqueness, foreign keys, vector dimensions, and view definitions. Install required extensions in an approved schema only after confirming extension relocation support.
- [ ] Grant `ford_catalog_reader` schema usage and SELECT only on the approved read relations. Explicitly revoke its write privileges and SELECT on private capture/provenance relations, including `ford_catalog.document_provenance_v1` migrated from `ford_read.document_provenance_v1`. Do not grant it `ford_metadata` access.
- [ ] Verify current Service DB user can run Liquibase and write catalog data as approved; record that this increases runtime application write capability.
- [ ] Verify the pre-existing `ford_catalog_reader` role exists in each database cluster. If absent, stop for approved DBA role provisioning; application Liquibase must not create cluster roles.
- [ ] Run disposable PostgreSQL integration tests for repeat migration, ownership, view definitions, and all reader privilege allow/deny probes.

**Verification:** Catalog DDL matches the reviewed scraper inventory; reader allowed/denied checks pass; no Service data relation is owned by or exposed to the catalog reader.

## Task 5: Generate and load immutable Liquibase catalog data

**Files:**
- Create: `ford-ai-service/scripts/catalog/export-liquibase-snapshot.py`
- Create: `ford-ai-service/scripts/tests/test_export_liquibase_snapshot.py`
- Create: `ford-ai-service/src/main/resources/db/changelog/catalog/data/catalog-snapshot-${sourceSha}.sql`
- Create: `ford-ai-service/src/main/resources/db/changelog/catalog/changes/003-catalog-snapshot-${sourceSha}.yaml`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordSchemaOwnershipIntegrationTest.java`

**Interfaces:** Export CLI:

```text
python3 scripts/catalog/export-liquibase-snapshot.py \
  --database-url "$FORD_CATALOG_SOURCE_JDBC_URL" \
  --source-database ford_catalog_candidate_20261025 \
  --output-dir build/catalog-liquibase
```

The command emits an immutable SQL data file and manifest. The Liquibase changeset includes the exact source SHA and an explicit `sqlFile` reference; it stages, validates, and atomically publishes only catalog-owned data.

- [ ] Write Python unit tests for wrong database identity, wrong source SHA, missing/extra object, count mismatch, unstable row order, SQL escaping, JSONB literals, 1,024-dimensional `bge-m3` vector literals, `tsvector` serialization, and deterministic output.
- [ ] Run `python3 -m unittest scripts.tests.test_export_liquibase_snapshot -v`; expected: failures identify the missing exporter.
- [ ] Implement read-only source introspection and deterministic INSERT generation with explicit columns, foreign-key order, safely quoted literals, and the full catalog inventory. Validate all catalog-document embeddings are `bge-m3`/1,024 dimensions and preserve `search_vector`. Never include credentials in artifacts or logs.
- [ ] Run `python3 -m unittest scripts.tests.test_export_liquibase_snapshot -v`; expected: all unit cases pass and repeated export from the same source yields identical SQL and hashes.
- [ ] Add a PostgreSQL integration test applying the snapshot changeset twice, verifying exact row counts/content hashes, failed-load rollback, and unchanged `ford_metadata` rows.
- [ ] Generate SQL only from the newly verified source archive/DB identity. Add it as a new immutable migration; never edit a previously applied data changeset.
- [ ] Run `./gradlew integrationTest --tests '*FordSchemaOwnershipIntegrationTest'`; expected: snapshot load, validation, rollback, and replacement tests pass.

**Verification:** Liquibase data resource SHA matches manifest; all catalog rows match the source; failed application leaves the active catalog and Service metadata intact.

## Task 6: Remove scraper-owned DDL execution only after parity

**Files (in a clean Ford-scraping checkout only):**
- Modify: `src/storage/database.ts`
- Modify: `src/cli.ts`
- Modify: SQL references in `src/storage/catalog-repository.ts`, `src/storage/search-repository.ts`, `src/storage/read-services.ts`, `src/storage/page-repository.ts`, `src/storage/document-repository.ts`, `src/storage/offer-repository.ts`, `src/storage/comparison-repository.ts`, `src/storage/sync-run-repository.ts`, `src/storage/captured-price-repository.ts`, and `src/storage/used-vehicle-repository.ts`
- Modify: `tests/integration/*` and `docs/operations.md`

- [ ] Do not edit the currently dirty detached checkout. Create a clean, reviewed task checkout from the approved Ford-scraping revision before touching these files; preserve all existing dirty work.
- [ ] Add tests proving startup does not call `runMigrations`, `migrate` refuses to execute legacy SQL after the cutover switch, and writer SQL targets only `ford_catalog`.
- [ ] Run `npm run test:unit` and focused integration tests; expected: fail on current migration startup and `ford.*` schema qualifiers.
- [ ] Change all scraper-owned SQL references from `ford.` to `ford_catalog.` only after the Liquibase DDL parity test passes. Keep no scraper DDL invocation in normal runtime startup.
- [ ] Preserve an explicit operator-only pre-cutover command for legacy DBs; remove it only after every environment is verified on Liquibase schema history.
- [ ] Run `npm run test:unit`, `npm run test:integration`, `npm run build`, and `npm run lint`; expected: all pass.

**Verification:** Scraper can read/write its catalog contract in `ford_catalog`, cannot change Liquibase-owned DDL, and all legacy schema migrations are no longer invoked after cutover.

## Stop Conditions

- Colima source database cannot be recovered without deleting or altering an in-use disk.
- Fresh snapshot identity or data counts are ambiguous; never substitute the different-SHA archive without review.
- Any required source table or captured evidence cannot be mapped into `ford_catalog` without loss.
- Liquibase metadata cannot be safely bootstrapped in `ford_metadata`, or an applied changeset would need editing.
- `public` contains unknown objects or an extension cannot be moved safely without `CASCADE`.
- Tests fail, writer/read-only grants differ from the approved contract, or unrelated dirty checkout files would be touched.
