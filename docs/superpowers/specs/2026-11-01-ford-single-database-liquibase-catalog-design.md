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
- Keep the built-in `public` schema present and empty of application and
  extension objects. Never drop `public` or use `DROP ... CASCADE`.
- `ford_metadata` holds Ford Web/Service operational data: bot and Meta chat
  sessions, lead/customer records, captured user requirements such as intent,
  warmth, budget, model interests, and notes, and tenant-scoped approved
  knowledge documents/embeddings. Liquibase tracking tables also live here.
- `ford_catalog` holds Ford-scraping catalog tables, catalog read relations,
  views, and private capture/provenance relations. Canonical Ford models, specs,
  prices, offers, promotions, and source-owned Ford catalog documents/embeddings
  belong here. Those catalog documents support deterministic SQL filters plus
  keyword and semantic search. Existing tenant-scoped `knowledge_document`,
  `knowledge_chunk`, and `public.vector_store` content remains in
  `ford_metadata`; do not mix it with source-owned Ford catalog documents.
  User-specific interest in a model or budget remains in
  `ford_metadata`; it is not canonical catalog data.
- Ford Service Liquibase is the sole DDL migration owner for both schemas.
  Ford-scraping stops applying its own schema migrations and writes only to the
  existing `ford_catalog` contract.
- Refactor Service's local and Ford MCP vehicle tools into clearly separated
  capabilities: user/session/lead-requirement data stays in `ford_metadata`,
  while Ford model/spec/price/offer/promotion reads come from `ford_catalog`
  through a dedicated `ford_catalog_reader` JDBC datasource. Rename or replace
  the confusing `vehicle_snapshot_search` tool as needed. Exact facts such as
  price and color use deterministic SQL; qualitative use-case questions such as
  family/work fit use keyword plus semantic vector search over catalog
  documents, combined with rank fusion. Service conversation-memory embeddings
  stay separate from Ford catalog embeddings. The scraper remains
  responsible for crawling and writing; do not remove its crawler or
  decommission its MCP endpoint until other consumers are checked.
- No trusted stock feed exists yet. Do not answer availability/stock questions
  from stale Service rows; route them to the human advisor through the existing
  handoff flow.
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
- For duplicate canonical catalog facts, the approved scraper snapshot wins as
  the current value. Existing Service catalog rows must be preserved with
  provenance/history; do not discard conflicts or expose them as current values
  without the approved source precedence.
- Never edit an applied Liquibase changeset. Each catalog snapshot is immutable,
  identified by its source SHA-256, and added as a new versioned migration.
- Keep catalog tools disabled until schema, data, role grants, Service health,
  and synthetic probes pass. Do not change DNS, Cloudflare, public routing, or
  scraper customer-facing behavior.

## Current context and blockers

- The user approved the fresh candidate source and its immutable archive:
  `ford_catalog_candidate_20261025`, PostgreSQL 16.15, archive SHA-256
  `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d`.
  The archive has 16 table/view relations and 10,983 base-table rows; all 12
  table row counts and canonical JSONL SHA-256 hashes match a successful
  disposable restore. The manifest and temporary archive are outside Git.
  All 1,882 catalog documents have ready `bge-m3` embeddings with 1,024
  dimensions and a precomputed `search_vector`.
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
  inventoried before moving tables. Classify existing vehicle/model/price
  relations by semantics: canonical Ford catalog facts belong in
  `ford_catalog`; chat, lead, and user-requirement facts belong in
  `ford_metadata`. Existing Liquibase history and lock tables must be preserved
  and moved to `ford_metadata` before Service starts with the new Liquibase
  schema setting; otherwise previously applied changesets could run again.
- This checkout has remote Ford MCP callbacks for catalog tools and a local
  `VehicleTools.vehicle_snapshot_search` backed by
  `JdbcVehicleCatalogRepository` and Service-owned tables. Refactor/replace this
  catalog adapter so canonical Ford models/prices/specs/offers come from
  `ford_catalog`; keep user/session/lead requirements in `ford_metadata`.
  Preserve semantics, not the confusing old tool name. Catalog reads use the
  reader identity.
- Ford-scraping's scheduler defaults to `0 12 1 * *` in
  `America/Mexico_City`. Its monthly sync crawls `/Todos/`, `/Seminuevos/`,
  `/Promociones/`, and `/Financiamiento/`, then ingests promotion/offer and
  financing data. Preserve and regression-test that schedule and ingestion.
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

The pre-Liquibase bootstrap must make `ford_metadata` available and, on an
existing database, move the already-applied `DATABASECHANGELOG` and
`DATABASECHANGELOGLOCK` tables from the verified current schema into
`ford_metadata` before Liquibase starts there. It must preserve all history,
fail on duplicate or unexpected tracking tables, and be idempotent. On a fresh
database it creates the schema and lets Liquibase create its tracking tables.
The bootstrap creates no application tables or catalog data. Liquibase then
owns application DDL and creates/manages `ford_catalog`. The built-in `public`
schema remains present but empty of application and extension objects after all
supported objects are reviewed and relocated. PostgreSQL system schemas remain.

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

When legacy Service catalog values conflict with the approved snapshot, retain
the legacy values as historical/provenance data while the snapshot supplies the
current canonical value. Validate row counts and history before retiring the
legacy catalog write path. Each subsequent catalog refresh adds a new immutable snapshot changeset. It
must not edit or checksum-clear a previously applied Liquibase changeset. The
snapshot changeset replaces catalog-owned state only; it never truncates or
rewrites `ford_metadata`.

### 3. Move databases and schemas

Before any target write, inventory each environment's databases, schemas,
relations, extensions, owners, grants, `search_path`, and data counts. Create
and verify an external backup of existing Service-owned data and a separate
catalog snapshot artifact. Restore/migrate into database `ford`. Move chat,
lead, and user-requirement tables into `ford_metadata`; move or reconcile
canonical Ford model/spec/price tables into `ford_catalog` alongside
scraper-owned catalog objects. Preserve user records and approved catalog rows.
Do not duplicate canonical catalog tables as competing sources of truth. Stop
if existing price records cannot be classified as canonical Ford prices versus
user-entered budget/requirements.

Keep `public` present and empty. Prove it contains no remaining application or
extension objects; move supported extensions into `ford_catalog` and verify all
dependent views/indexes. If an object cannot be safely relocated or removed
without `CASCADE`, stop and require owner review. Never drop `public`.

After cutover, configure both Service datasources to the same `ford` database:
the primary datasource uses `ford_metadata`; the catalog datasource uses
`ford_catalog` and username `ford_catalog_reader`. Replace the current remote
Ford MCP catalog tool callbacks with local JDBC-backed Service tools. Refactor
the local tool surface so conversational/user requirement data remains in
`ford_metadata`, while canonical Ford model/spec/price/offer/promotion data
comes from `ford_catalog` using only `ford_catalog_reader`; rename or replace
`vehicle_snapshot_search` as needed. Keep user-entered budget and interests
distinct from catalog prices and specifications. Keep the scraper crawler and
its write path. Preserve
the monthly schedule `0 12 1 * *` in `America/Mexico_City` and verify the
monthly sync still crawls `/Promociones/` and `/Financiamiento/`, ingests offers,
and retains validity/expiry behavior. Do not decommission the scraper MCP
endpoint until its other consumers are checked. The user-selected shared
primary/Liquibase identity retains catalog write access; the read adapter must
use only the reader identity.

### 4. Deploy and verify

Revalidate immutable Service and Web release identities and protected approvals.
Deploy through existing protected workflows; bypass no gate and create no
`emme-test` environment. Keep catalog tools disabled during database and
application cutover. Verify health, datasource database/schema identity,
Liquibase changelog status, catalog reader SELECT access, denied reader writes,
denied capture access, that Service has no Ford MCP dependency for these tools,
and unchanged Service metadata. Verify monthly promotion/financing sync remains
active before catalog tools are enabled. Enable tools only after
synthetic exact-model, trim, offer/promotion, citation, price, and no-match
checks pass.

Catalog queries use parameterized deterministic SQL for structured facts
(including price, color, model, and year) and keyword/vector retrieval over
`catalog_documents_v1` for qualitative intent (for example family use or work
use). Merge keyword and semantic rankings with the existing reciprocal-rank
fusion pattern; retain source URLs for citations. The catalog query embedder
must be compatible with the approved `bge-m3` 1,024-dimensional vectors and
must not change Service conversation-memory embeddings. Until a trusted stock
feed exists, availability questions use human handoff and make no stock claim.

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
  it cannot be made empty without `CASCADE`.
- Reader can write or read private capture/provenance relations; Service data is
  exposed to the catalog tool identity; or the shared app DB user's increased
  catalog write scope has not been reviewed as an accepted risk.
- Backup/restore proof, tests, immutable artifact provenance, health checks,
  protected approval, or rollback artifact is missing.
- A production gate or route change is needed, or customer-facing data checks
  fail. Stop before cutover or tool enablement.

## Acceptance

- Each environment has one application database named `ford` and exactly two
  application schemas: `ford_metadata` and `ford_catalog`; built-in `public`
  remains present and contains no application or extension objects, and
  PostgreSQL system schemas remain.
- Liquibase is the only schema-migration owner. Ford-scraping no longer applies
  its custom DDL migrations and writes only to its `ford_catalog` contract.
- Chat, lead, and user-requirement records remain in `ford_metadata`; canonical
  Ford catalog records and relations match the reviewed manifest in
  `ford_catalog` with no competing model/price source.
- Liquibase tracks every schema/snapshot migration; no applied changeset is
  edited to refresh catalog data.
- Catalog tools read only through `ford_catalog_reader`; writes and private
  capture reads are denied to that role.
- Existing protected Service/Web release gates remain intact; catalog tools
  turn on only after all checks pass.
- Service no longer depends on Ford MCP for the current catalog tool set; its
  local catalog tools use `ford_catalog_reader`; chat, lead, and user
  requirement data remains backed by `ford_metadata`.
- Catalog search combines exact SQL facts with keyword and semantic retrieval
  over catalog documents; query embeddings match the stored `bge-m3` 1,024-
  dimensional vectors. Stock availability stays with human advisor handoff
  until a trusted stock feed exists.
- Candidate coverage/price gaps remain documented; no production-readiness
  claim is made.
