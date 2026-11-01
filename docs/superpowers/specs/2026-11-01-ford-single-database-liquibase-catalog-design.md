# Ford Single-Database and Liquibase Catalog Design

## Goal

Use one application database named `ford` in every environment. Keep Service-owned
data in `ford_metadata`, Ford-scraping/catalog data in `ford_catalog`, and make
Ford Service Liquibase the only schema-migration owner. Liquibase provisions
both schemas and loads the selected catalog snapshot as a versioned migration.

## Confirmed decisions

- Database name is `ford`; do not retain `ford_service` after successful
  migration. Keep a verified external backup and restore proof for rollback.
- The only application schemas are `ford_metadata` and `ford_catalog`.
  PostgreSQL system schemas (`pg_catalog`, `information_schema`, and internal
  schemas) are not application schemas and remain present.
- Remove the built-in `public` schema after verifying it contains no required
  objects and relocating required extensions. Never use `DROP ... CASCADE`.
- `ford_metadata` holds Ford Service tables and Liquibase tracking tables.
- `ford_catalog` holds Ford-scraping catalog tables, catalog read relations,
  views, and private capture/provenance relations. Service's existing
  `vehicle_model`-style data remains in `ford_metadata`; it is not overwritten
  with scraper rows.
- Ford Service Liquibase is the sole DDL migration owner for both schemas.
  Ford-scraping stops applying its own schema migrations and writes only to the
  existing `ford_catalog` contract.
- Ford Service replaces all current Ford MCP catalog tool calls with local
  Service tools backed by a dedicated `ford_catalog_reader` JDBC datasource.
  The scraper remains responsible for crawling and writing; do not remove its
  crawler or decommission its MCP endpoint until other consumers are checked.
- The same Service database user runs Liquibase and the Service. This user will
  gain catalog DDL/DML privileges. `ford_catalog_reader` remains a separate
  read-only identity for catalog retrieval; it cannot read capture/provenance
  relations or write to either schema.
- Liquibase's master changelog includes the catalog changelog in every
  environment, including production. Catalog schema and snapshot migrations
  therefore apply during normal Service Liquibase updates.
- The selected local catalog candidate is test-quality data with known coverage
  and historical-price gaps. The user explicitly approved loading that snapshot
  into production. This approval does not make the data production-validated.
- Never edit an applied Liquibase changeset. Each catalog snapshot is immutable,
  identified by its source SHA-256, and added as a new versioned migration.
- Keep catalog tools disabled until schema, data, role grants, Service health,
  and synthetic probes pass. Do not change DNS, Cloudflare, public routing, or
  scraper customer-facing behavior.

## Current context and blockers

- The previous exact source dump had SHA-256
  `5cd8cdbe3d14fc93b3e1ab251b449d425cf47e21b65accdc31542ed3ad6157a9`, but its
  temporary file is no longer present. The user approved generating a fresh
  dump; the new source identity and counts must be captured before migration.
- A different candidate archive with SHA-256 beginning `b0b45e6e` exists in a
  separate, dirty Ford-scraping checkout. Do not substitute it without review.
- The local Colima `testbox` profile is stopped. Startup failed because its disk
  was reported in use, while Lima/Colima reported no running instance. Do not
  remove locks, recreate the profile, or alter its disk without investigation
  and approval.
- The active `Ford-uruapan` checkout is on
  `chore/platform-secret-management` and contains unrelated user modifications.
  Preserve those changes; add only task-scoped files.
- This checkout's Service Liquibase changelog creates current tables with
  unqualified names. The actual current schema and `search_path` must be
  inventoried before moving Service tables.
- This checkout's current Service catalog tools are remote Ford MCP callbacks;
  there is no direct `ford_catalog` JDBC adapter yet. Local Service tools must
  preserve the existing Ford tool names and behavior while using the reader
  identity.
- Ford-scraping currently uses a custom Node migration runner over
  `db/migrations/*.sql`; it is not using Liquibase. The scraper migration runner
  and DDL must be retired or migrated only after Liquibase parity is proven.

## Target layout

```text
PostgreSQL application database: ford
  ford_metadata  Service-owned tables and Liquibase DATABASECHANGELOG tables
  ford_catalog   scraper-owned catalog tables, read relations, and private
                 capture/provenance relations
```

The database bootstrap must make `ford_metadata` available before Liquibase
creates its tracking tables there. Liquibase then owns application DDL and
creates/manages `ford_catalog`. This bootstrap is idempotent and creates no
tables or catalog data. The `public` schema is removed only after all its
objects and extension dependencies are accounted for. PostgreSQL system schemas
remain.

The final accepted database has no application tables outside the two named
schemas. Temporary staging objects may exist during a migration but must be
removed before acceptance. The old `ford_service` database is not a post-cutover
rollback target; rollback uses the verified external backup.

## Migration design

### 1. Establish one DDL source of truth

Create a dedicated catalog changelog under
`ford-ai-service/src/main/resources/db/changelog/`. Port the required DDL from
Ford-scraping migrations into Liquibase changesets with explicit schema names.
Set Service Liquibase and JDBC defaults to `ford_metadata`; use explicit
`ford_catalog` qualification for catalog objects. Preserve the scraper SQL
migration history as reference until parity tests prove each required table,
constraint, index, extension, and view is represented.

Ford-scraping must not run its old SQL migrations after cutover. Its runtime
identity writes only the catalog relations required for crawling and ingestion.
Do not drop scraper-owned data as part of removing its migration runner.

### 2. Load the snapshot through Liquibase

Generate a fresh source dump from the verified local candidate database. Record
source database identity, SHA-256, PostgreSQL and extension versions, exact
object inventory, per-table counts and content hashes, and candidate limitations.
Create deterministic SQL data resources from that verified snapshot and
reference them from immutable Liquibase changesets. The changesets must load
tables in foreign-key order and include explicit column lists. Do not put
credentials in the changelog or data resources.

Load a new snapshot into a staging catalog namespace, validate all expected
objects, row counts, content hashes, foreign keys, and representative reads,
then perform the cutover transactionally. Keep staging isolated from Service
traffic until validation passes. Failed changesets must leave the currently
active catalog readable. Retain the previous state in the verified external
backup; do not retain an extra application database or schema in the accepted
final layout.

Each subsequent catalog refresh adds a new immutable snapshot changeset. It
must not edit or checksum-clear a previously applied Liquibase changeset. The
snapshot changeset replaces catalog-owned state only; it never truncates or
rewrites `ford_metadata`.

### 3. Move databases and schemas

Before any target write, inventory each environment's databases, schemas,
relations, extensions, owners, grants, `search_path`, and data counts. Create
and verify an external backup of existing Service-owned data and a separate
catalog snapshot artifact. Restore/migrate into database `ford`, move existing
Service tables into `ford_metadata`, and place scraper-owned catalog objects
in `ford_catalog`. Preserve Service rows and catalog rows exactly as approved.

Before removing `public`, prove it contains no required non-extension objects,
move or recreate supported extensions in `ford_catalog`, and verify all
dependent views/indexes. If an extension cannot be safely relocated, stop; do
not cascade-drop objects. Drop `public` only after a no-cascade drop succeeds.

After cutover, configure both Service datasources to the same `ford` database:
the primary datasource uses `ford_metadata`; the catalog datasource uses
`ford_catalog` and username `ford_catalog_reader`. Replace the current remote
Ford MCP catalog tool callbacks with local JDBC-backed Service tools while
preserving their tool names and contracts. Keep the scraper crawler and its
write path. Do not decommission the scraper MCP endpoint until its other
consumers are checked. The user-selected shared primary/Liquibase identity
retains catalog write access; the read adapter must use only the reader
identity.

### 4. Deploy and verify

Revalidate immutable Service and Web release identities and protected approvals.
Deploy through existing protected workflows; bypass no gate and create no
`emme-test` environment. Keep catalog tools disabled during database and
application cutover. Verify health, datasource database/schema identity,
Liquibase changelog status, catalog reader SELECT access, denied reader writes,
denied capture access, that Service has no Ford MCP dependency for these tools,
and unchanged Service metadata. Enable tools only after
synthetic exact-model, trim, offer/promotion, citation, price, and no-match
checks pass.

Production receives the approved candidate snapshot despite its known data
limitations. Report these limitations in the release record and do not claim
the candidate is production-validated.

## Verification and rollback

- Unit-test the catalog changelog contract, generated snapshot SQL, migration
  immutability, and schema allowlists.
- Run Liquibase against disposable PostgreSQL before any environment migration.
  Verify create, repeat-update, failed-load rollback, snapshot replacement, and
  `public` removal refusal when unexpected objects exist.
- Integration-test both schemas, the exact reader ACL, Service data
  preservation, scraper write contract, and views after the cutover.
- Before each environment, require a fresh backup checksum and isolated restore
  proof. Verify disk space, exact target DB identity, migration lock state, and
  no unexpected schemas.
- On failure, keep tools disabled. Restore the verified external backup into
  database `ford`; do not rely on the old `ford_service` database remaining.
- Do not retire the backup until the migration is accepted and rollback
  retention is explicitly reviewed.

## Stop conditions

- Candidate source is unavailable, its identity/counts are unclear, or a
  different SHA is substituted without review.
- Service metadata or scraper data cannot be mapped without loss or ambiguity.
- The Liquibase DDL/data changeset differs from the approved migration
  inventory, or a snapshot changes an already-applied changeset.
- `public` contains unknown objects, extensions cannot be safely relocated, or
  `DROP SCHEMA public` would require `CASCADE`.
- Reader can write or read private capture/provenance relations; Service data is
  exposed to the catalog tool identity; or the shared app DB user's increased
  catalog write scope has not been reviewed as an accepted risk.
- Backup/restore proof, tests, immutable artifact provenance, health checks,
  protected approval, or rollback artifact is missing.
- A production gate or route change is needed, or customer-facing data checks
  fail. Stop before cutover or tool enablement.

## Acceptance

- Each environment has one application database named `ford` and exactly two
  application schemas: `ford_metadata` and `ford_catalog`; `public` is absent
  and PostgreSQL system schemas remain.
- Liquibase is the only schema-migration owner. Ford-scraping no longer applies
  its custom DDL migrations and writes only to its `ford_catalog` contract.
- Service-owned records remain in `ford_metadata`; catalog snapshot records and
  relations match the reviewed manifest in `ford_catalog`.
- Liquibase tracks every schema/snapshot migration; no applied changeset is
  edited to refresh catalog data.
- Catalog tools read only through `ford_catalog_reader`; writes and private
  capture reads are denied to that role.
- Existing protected Service/Web release gates remain intact; catalog tools
  turn on only after all checks pass.
- Service no longer depends on Ford MCP for the current catalog tool set; its
  local tools use `ford_catalog_reader` and preserve the approved tool contract.
- Candidate coverage/price gaps remain documented; no production-readiness
  claim is made.
