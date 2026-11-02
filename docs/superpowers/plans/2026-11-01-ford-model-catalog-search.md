# Ford Model Catalog Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make qualitative search model-level, return exact matching trim facts, and combine semantic and keyword retrieval in one PostgreSQL statement.

**Architecture:** Keep one logical catalog per model. Searchable source chunks retain their own citation and `bge-m3` vector and link to a stable model identity; year and trim facts stay structured. One SQL statement chooses each model's best document in each ranking channel before applying per-model candidate bounds, fuses model ranks with RRF, applies exact structured filters, and returns matching trims plus best semantic/keyword evidence for in-memory grouping. Keep app data and Liquibase history in `ford_metadata`, catalog relations and pgvector in `ford_catalog`, and leave built-in `public` empty.

**Tech Stack:** Java 21, Spring JDBC `JdbcClient`, PostgreSQL 16, pgvector, Liquibase, JUnit 5, AssertJ, Testcontainers.

## Global Constraints

- “Catalog is logically organized by model, not by trim.”
- “Qualitative search finds a model first; exact SQL filters determine which model-year and trim records are returned.”
- “Trim-level searchable documents are added only when a source contains materially distinct information for that trim.”
- “Embeddings stay on searchable catalog documents, not on model or trim rows.”
- “One document represents one coherent source text/chunk, with its own citation and vector. A logical model catalog can have multiple such documents.”
- “Use the currently approved embedding contract: `bge-m3`, 1,024 dimensions. Do not combine catalog vectors with conversation-memory vectors.”
- “Combine vector and Spanish keyword ranks with reciprocal-rank fusion (RRF) inside one parameterized PostgreSQL query. Query embedding generation is a separate model call, not a second database query.”
- “Structured facts (including price, color, year, and trim) remain authoritative SQL data. Semantic similarity must not invent or override exact values.”
- “Preserve source URLs on every returned document/fact used for an answer.”
- “Do not alter approved source archive bytes, approved manifest hashes, or an applied Liquibase changeset to make the design fit current exporter output.”
- “Keep catalog tools disabled until existing snapshot-integrity and release gates pass. No deployment, scraper behavior, routing, DNS, or Cloudflare changes are part of this work.”
- All application-owned objects, including Liquibase history and the pgvector extension, stay out of `public`; keep `public` present but empty.
- Install or relocate pgvector and pg_trgm into `ford_catalog`; do not use pg_trgm for current query ranking; keep reader without `USAGE` on `ford_metadata`.
- Configure JDBC search paths without `public`; qualify vector types, operators, and operator classes.
- Audit every object class in `public`; move only reviewed app objects and fail closed for unclassified objects.
- Do not use `CASCADE` or drop the built-in `public` schema.
- The exporter hash mismatches for `ford_capture.retrieval_document_manifest` and `ford_read.catalog_documents_v1` remain a hard stop for snapshot generation/loading.
- Never drop the built-in `public` schema. Do not use `CASCADE`; fail closed if any unreviewed user object remains there.
- Do not claim stock availability or call candidate data production-validated.
- Preserve the scraper's source relations and monthly sync behavior.
- The reader continues using `ford_catalog_reader`, with read-only access to catalog read relations only. It must not access metadata, capture, or provenance relations.
- No deployment, routing, DNS, Cloudflare, `emme-test`, or customer-facing scraper change.
- Preserve unrelated workspace modifications; commit only files named in each task.

---

## File Map

- Modify `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogKnowledgeReader.java` — model-scoped hybrid SQL, deterministic RRF, exact trim filtering, and row grouping without a second database call.
- Create `ford-ai-service/src/test/java/com/ford/service/catalog/adapter/out/OllamaCatalogQueryEmbeddingModelTest.java` — lock down the existing catalog embedding contract for blank input, null/wrong-dimension output, and non-finite values.
- Reuse `ford-ai-service/src/main/java/com/ford/service/catalog/domain/CatalogDocumentHit.java` — retain per-document evidence and citation fields without changing the citation contract.
- Create `ford-ai-service/src/main/java/com/ford/service/catalog/domain/CatalogModelSearchResult.java` — model identity, exact matching trim facts, and supporting document hits.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/FordCatalogTools.java` — return model-level results from `ford_catalog_hybrid_search` without changing tool-off behavior.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/JdbcFordCatalogReaderIntegrationTest.java` — model-only documents, exact filters, grouping, citation, one-query, and grants.
- Create `ford-ai-service/src/main/resources/db/changelog/changes/026-ford-extension-schemas.yaml` — install/relocate vector and pg_trgm into `ford_catalog` after the metadata cutover and before catalog DDL.
- Modify `ford-ai-service/src/main/resources/db/changelog/changes/025-ford-metadata-schema.yaml` only if Liquibase proves it unapplied — preserve `ford_catalog` extension placement and do not alter applied history.
- Modify `ford-ai-service/src/main/resources/db/changelog/catalog/changes/001-ford-catalog-schema.yaml` — define the document embedding type as `ford_catalog.vector(1024)`.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordPostgresContainer.java` — provision synthetic metadata/catalog schemas, pgvector in `ford_catalog`, and a no-public test search path.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/database/SchemaIntegrationTest.java` — assert pgvector placement, empty-public invariant, and reader permissions.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/knowledge/PgVectorKnowledgeIntegrationTest.java` — use metadata schema and approved 1,536D vectors.
- Reuse `ford-ai-service/src/main/java/com/ford/service/database/FordMetadataLiquibaseBootstrap.java` and `ford-ai-service/src/main/java/com/ford/service/database/FordLiquibaseConfiguration.java` — ensure Liquibase history is moved/created in `ford_metadata` before migration execution.
- Create a new file under `ford-ai-service/src/main/resources/db/changelog/catalog/changes/` only for a verified, required index or identity-map change; include its integration test in the same task.

## Task 1: Prove the model identity mapping

**Files:** None. Read-only inspection only.

**Consumes:** Approved archive and manifest paths recorded in the model-catalog design spec.

**Produces:** Written evidence that model-level catalog documents map unambiguously to the structured model/year/variant relations, or a stop report identifying the missing mapping. No inferred or fuzzy mapping is allowed.

- [x] Inspect source identifiers, nullability, and uniqueness in a disposable restore

Run read-only catalog queries against the disposable approved-archive restore:

```sql
begin read only;
select count(*) as document_count,
       count(*) filter (where model_id is null) as documents_without_model_id,
       count(distinct model_id) as distinct_model_ids
from ford_read.catalog_documents_v1;

select count(*) as document_count,
       count(*) filter (where model_year_id is null) as documents_without_model_year_id,
       count(distinct model_year_id) as distinct_model_year_ids
from ford_read.catalog_documents_v1;

select count(*) as variant_documents,
       count(*) filter (where v.variant_id is null) as unresolved_variant_documents
from ford_read.catalog_documents_v1 d
left join ford_read.vehicle_variants_v2 v on v.variant_id = d.variant_id
where d.variant_id is not null;
rollback;
```

Also inspect view/table definitions for the model/year/variant identifiers and verify any proposed join has no ambiguous matches. Do not write to the restore.

**Actions Applied**
- Read-only inspection of the running disposable candidate restore. `catalog_documents_v1` has 1,882 rows; 1,880 carry both `model_id` and `model_year_id`; 30 distinct model IDs each map to exactly one `vehicle_search_v1.normalized_name`.
- All 1,880 non-null document model-year links resolve; all 942 non-null variant links resolve. One model-year row lacks a document, but its exact normalized model has catalog documents for other years.
- The remaining two docs link by `offer_id`; all 10 offer docs join one offer, and exact normalized model+year joins each to one `vehicle_search_v1` row and one model ID. This is exact canonical equality, not fuzzy matching.

**Verification**
- Every catalog document maps to exactly one model using model ID + model-year, or offer ID + exact model/year for offer-only docs; zero ambiguous IDs in the restore.
- All SQL ran inside `BEGIN READ ONLY` and ended with `ROLLBACK`.

- [x] Verify snapshot identity and hash stop conditions

Compare archive SHA, table inventory, counts, and all relation hashes to the approved manifest using the fail-closed exporter. Expected today: it may still stop on the two documented hash mismatches. If any mismatch remains, record it; do not change expected hashes or generate a snapshot changeset.

**Actions Applied**
- Reran fail-closed exporter with approved archive SHA and disposable candidate database.
- No manifest hashes edited; no snapshot changeset generated.

**Verification**
- Exporter again refused on `ford_capture.retrieval_document_manifest` and `ford_read.catalog_documents_v1` only. Identity research may continue with the disposable restore; snapshot generation/loading remains blocked.

- [x] Stop if model identity is not stable

If source IDs cannot map every model-level document to exactly one structured model identity, do not implement a name-based or fuzzy join. Report the exact unmapped/ambiguous rows and request source-owner resolution. Resume implementation only after an explicit stable identity mapping is available and documented.

**Actions Applied**
- Confirmed exact, unique mapping for all documents, including the 2 offer documents with null model/year IDs, using their `offer_id` then exact model/year.

**Verification**
- No mapping stop condition triggered. Snapshot hash stop condition remains active.

**Verification:** Read-only query output proves each searchable document has one stable model identity or the task stops safely. Hash discrepancies remain unchanged.

## Task 2: Add failing model-level integration tests

**Files:** `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/JdbcFordCatalogReaderIntegrationTest.java`

**Consumes:** Stable model join identified in Task 1.

**Produces:** Test fixtures proving a model-level document with null `variant_id` is searchable, while exact filters return only eligible trim rows.

- [x] Add a fixture with one model-level document and two structured trims

Use one model identity, one source document with `variant_id = null`, two variants with different prices/colors, and distinct source URLs. Use `bge-m3`, 1,024-dimensional fixture vectors as existing test helpers do.

Extend the existing `insertDocument` test helper with an overload accepting `modelId`, `modelYearId`, `variantId`, and `offerId`; keep the current helper as a convenience wrapper so existing tests retain their fixture behavior.

**Actions Applied**
- Added nullable-model/year/variant/offer document fixture support and model-level document with `variant_id = null`.

**Verification**
- The model-filter test first failed on the old variant-dependent model predicate, then passed after filtering through model-year identity.

- [x] Add tests for model document retrieval and exact trim selection

Assert that a model filter finds the model-level document despite the null variant link. Assert that year, price-range, and color filters include only the structured trim that exactly matches. Assert that unsupported color does not produce a trim merely because its document is semantically close.

**Actions Applied**
- Added tests for model scope, exact model-year, price, color, trim facts, citation, and model grouping.

**Verification**
- `JdbcFordCatalogReaderIntegrationTest` passes these assertions with PostgreSQL Testcontainers.

- [x] Add grouping and evidence assertions

Add multiple chunks for one model and one chunk for a second model. Assert one model result per model, best-supporting-document ranking, retained citation URL, and matching trim facts grouped under the right model.

**Actions Applied**
- Added an integration case proving multiple chunks group into one model result with exact price/color/year trim filtering and citations.
- Added offer-only document mapping and a 25-chunk crowd-out regression test.

**Verification**
- Model-level, offer mapping, exact trim, citation, model de-duplication, and per-model candidate-bound integration tests pass.

- [x] Prove invalid vectors fail before JDBC

Build a reader with the counting `JdbcClient` and an embedding model returning a wrong-length vector; assert `searchHybrid` throws `IllegalArgumentException` and the prepared-statement count remains zero. Repeat for a vector containing `Float.NaN`.

**Actions Applied**
- Added wrong-dimension and non-finite vector integration probes using a counted datasource.

**Verification**
- Both errors occur before JDBC; prepared-statement count remains zero.

- [x] Prove blank queries and non-positive limits make no embedding or JDBC call

Use an embedding-model lambda that fails if invoked and the counting datasource. Assert blank text and limits `0` and `-1` return an empty list and leave the prepared-statement count at zero.

**Actions Applied**
- Added empty-text and zero/negative-limit no-call checks.

**Verification**
- All return empty; no embedding call or SQL statement occurs.

- [x] Run tests and confirm expected red

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew integrationTest --tests '*JdbcFordCatalogReaderIntegrationTest'
```

Expected: new model-level retrieval/grouping assertions fail against the current variant-dependent filter/result contract; existing integration tests compile and run.

**Actions Applied**
- Ran the model-level null-variant test before changing its production predicate.

**Verification**
- Observed expected assertion failure: model-level document was omitted because current filter required `variant_id`.

## Task 3: Implement model-level search result contract

**Files:** Create `ford-ai-service/src/main/java/com/ford/service/catalog/domain/CatalogModelSearchResult.java`; modify `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/FordCatalogTools.java`; reuse `CatalogDocumentHit.java` unchanged.

**Consumes:** Task 1 stable model identity and Task 2 red tests.

**Produces:** A model-level result containing exact matching variants and cited supporting documents.

- [x] Add unit tests for the catalog embedding contract

Test `OllamaCatalogQueryEmbeddingModel` using a mocked Spring `EmbeddingModel`. Cover a valid 1,024-value vector, null output, wrong dimension, `NaN`, and positive/negative infinity. Valid output is returned unchanged; invalid output fails before the JDBC reader can execute SQL.

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew test --tests '*OllamaCatalogQueryEmbeddingModelTest'
```

Expected: all embedding contract tests pass.

**Actions Applied**
- Added four unit tests for valid vectors, blank input, null/wrong dimensions, and non-finite values.

**Verification**
- `./gradlew test --tests '*OllamaCatalogQueryEmbeddingModelTest'` passes.

- [x] Add an integration assertion for the model result API before implementation

Update the model-level integration test to assert the returned model ID/name, matching variant list, and source-document list. The test should fail compilation because the model result type and reader/tool signature do not exist yet; do not add production code until this assertion is written.

**Actions Applied**
- Added a model-grouped result assertion before defining the result type.

**Verification**
- Observed expected compile failure for missing `CatalogModelSearchResult` and model-result accessors.

- [x] Run the integration test and observe the expected compile failure

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew integrationTest --tests '*JdbcFordCatalogReaderIntegrationTest'
```

Expected: compilation fails only because the desired model-result contract is not implemented yet.

**Actions Applied**
- Ran the focused integration test before adding the result record/API.

**Verification**
- Compile failed on the missing desired model-level result symbols, as expected.

- [x] Define immutable result records

Add this public record; copy list inputs so the result is immutable:

```java
package com.ford.service.catalog.domain;

import java.util.List;
import java.util.UUID;

public record CatalogModelSearchResult(
        UUID modelId,
        String model,
        List<CatalogFact> matchingVariants,
        List<CatalogDocumentHit> sources) {
    public CatalogModelSearchResult {
        matchingVariants = List.copyOf(matchingVariants);
        sources = List.copyOf(sources);
    }
}
```

Keep `CatalogDocumentHit` as the source-level evidence record; do not remove `documentId`, `sourceUrl`, or optional year/variant/offer references.

**Actions Applied**
- Added immutable `CatalogModelSearchResult` with model identity, exact matching variants, and source evidence.

**Verification**
- Java compilation and focused integration suite pass.

- [x] Change the hybrid tool return type

Change `FordCatalogTools.hybridSearch(...)` to return `List<CatalogModelSearchResult>` from `knowledgeReader.searchHybrid(...)`. Preserve tool name, input fields, safe result bound, citations, feature flag, and all exact-search/offer tool behavior.

**Actions Applied**
- Changed hybrid reader and Spring AI tool to return model-grouped result contract; added tool delegation test.

**Verification**
- Focused catalog-tool and configuration unit tests pass; tool stays disabled by default.

- [x] Run focused compilation and tool tests

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew test --tests '*FordCatalogToolsTest' --tests '*CatalogToolConfigurationTest'
```

Expected: PASS, with catalog tools still absent unless explicitly enabled and both reader beans exist.

**Actions Applied**
- Ran embedding and catalog tool unit suites.

**Verification**
- `BUILD SUCCESSFUL`.

## Task 4: Fuse and group model results in one SQL statement

**Files:** Modify `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogKnowledgeReader.java` and the integration test.

**Consumes:** Task 1 stable join, Task 2 tests, Task 3 result records.

**Produces:** One prepared catalog SQL statement that returns unique model-level results, exact matching trims, and citations.

- [x] Extend the failing fixture tests to assert exactly one prepared statement

Use the existing `CountingDataSource` helper. Reset/create its counter immediately before `searchHybrid`; assert one prepared statement for a non-empty hybrid request. Keep embedding generation outside this database-call count.

**Actions Applied**
- Kept the prepared-statement counter isolated around one hybrid call.

**Verification**
- Integration test proves exactly one catalog statement for semantic+keyword hybrid search.

- [x] Replace variant-dependent model filtering with the validated model join

In both semantic and keyword candidate CTEs, join/filter through the stable model identity established in Task 1. Do not require `d.variant_id` for a model filter. Apply year and exact trim/price/color predicates through structured catalog relations, never from semantic text.

**Actions Applied**
- Resolved model documents through stable `model_id` + normalized model-year identity. Offer-only docs map by `offer_id` and exact model/year.

**Verification**
- Integration tests prove null-variant model documents and offer-only documents are searchable under exact model/year filters.

- [x] Compute document RRF and model score in SQL

For each ranking channel, use `row_number() partition by model_id` to keep that model's best document, then rank the distinct models and apply `candidateLimit`. Assign deterministic document/model-ID tie breaks. Apply RRF to semantic and keyword model ranks. Return the best document from each channel as evidence, deduplicating when both channels select the same document. Do not let models with many chunks use multiple candidate slots.

**Actions Applied**
- Ranked models per channel before candidate limits; fused the channel-level model ranks with RRF and retained best evidence per channel.

**Verification**
- 25 chunks for one model do not crowd a second model out of the bounded candidate list.

- [x] Return supporting document and eligible trim rows in the same SQL statement

Join top model results to exact model-year/variant/fact relations. Return source URL and evidence fields. Let the adapter group result rows into `CatalogModelSearchResult` in memory; do not issue a second SQL query to load trims or citations.

**Actions Applied**
- One SQL statement returns model IDs, evidence documents, and exact matching trim facts; adapter groups rows in memory.

**Verification**
- Citation, price, color, year, trim, and model grouping integration assertions pass.

- [x] Run the focused integration test and verify green

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew integrationTest --tests '*JdbcFordCatalogReaderIntegrationTest'
```

Expected: model-only document, exact trim filtering, RRF, grouping, citation, one-statement count, and reader permission assertions all pass.

**Actions Applied**
- Ran the full focused catalog reader integration class after the SQL refactor.

**Verification**
- 13 integration tests pass, including one-query, per-model bounds, exact filters, offer-only mapping, and reader grants.

## Task 5: Validate query plan and add only justified indexes

**Files:** Modify the catalog Liquibase changelog only if the candidate plan proves an index is missing and can be added without changing approved source rows; otherwise create no migration file.

**Consumes:** Task 4 final SQL and the disposable archive restore. If approved hashes remain unresolved, treat every plan as diagnostic only and make no schema/index change.

**Produces:** Documented `EXPLAIN (ANALYZE, BUFFERS)` evidence and, only if needed, a forward-only Liquibase index changeset.

- [x] Capture representative query plan (diagnostic only)

Run `EXPLAIN (ANALYZE, BUFFERS)` for vector per-model candidate ranking and Spanish keyword ranking on the disposable restore. Inspect candidate scans and sorts. While either approved relation hash is unresolved, label the output diagnostic only; it does not validate the exact approved dataset or production-scale performance.

**Actions Applied**
- Captured per-channel vector and keyword plans on the disposable restore. No indexes were present on the inspected source relations.

**Verification**
- Vector ranking scanned 1,882 documents and completed in about 19.7 ms; keyword ranking scanned 1,882 documents, matched 37, and completed in about 1.8 ms. Both used sequential scans/sorts. Treat as diagnostic only because two approved relation hashes still mismatch.

- [x] Add only demonstrated index gaps

Only after every approved source hash matches, and if the verified plan shows a material missing access path, add one new catalog Liquibase changeset with explicitly qualified, non-destructive `CREATE INDEX` statements. Candidate index types are HNSW cosine on compatible ready embeddings, GIN on `search_vector`, and B-tree on validated model/year/variant join keys. Do not add redundant indexes. Do not edit changeset `001` or `002`, drop schema objects, or use `CASCADE`.

**Actions Applied**
- Added no index or DDL changeset. Hash gate is unresolved, so the diagnostic scan cannot justify a production schema change.

**Verification**
- Existing plan shows small-candidate sequential scans; no index claim or snapshot change made. Reassess after approved hashes match.

- [x] Add integration assertions for each new index (not applicable)

No indexes were added, so no index catalog assertion is needed. Preserve the diagnostic plan output above; add catalog assertions only alongside a future, hash-gated index changeset.

**Actions Applied**
- No index changeset created.

**Verification**
- No schema or index DDL changed.

**Verification:** Each created index exists, the changeset applies idempotently through Liquibase test setup, and reader tests pass. If indexes are not justified, record the plan and create no DDL.

## Task 6: Relocate pgvector and keep `public` empty

**Files:** Create `ford-ai-service/src/main/resources/db/changelog/changes/026-ford-extension-schemas.yaml`; modify `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`, `ford-ai-service/src/main/resources/db/changelog/catalog/changes/001-ford-catalog-schema.yaml`, `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogKnowledgeReader.java`, `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceProperties.java`, conversation vector adapters, `application.yml`, Testcontainers, and schema/catalog/vector integration tests. Keep 025 unchanged; it is an existing forward-only changeset that moves vector to `public`, so 026 runs after it and before catalog DDL.

**Consumes:** Approved schema policy in `docs/superpowers/specs/2026-11-01-ford-model-catalog-search-design.md` and a read-only object inventory.

**Produces:** pgvector and pg_trgm extension objects in `ford_catalog`; app tables and Liquibase history in `ford_metadata`; no app-owned objects or extension objects in `public`; reader remains denied metadata. pg_trgm stays unused by current hybrid ranking.

- [x] Audit `public` and extension ownership on the disposable restore

Run on the disposable approved-archive restore:

```sql
begin read only;
select n.nspname, c.relkind, c.relname, pg_get_userbyid(c.relowner) as owner
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' order by c.relkind, c.relname;
select n.nspname, p.proname, pg_get_userbyid(p.proowner) as owner
from pg_proc p join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public' order by p.proname;
select n.nspname, t.typname, pg_get_userbyid(t.typowner) as owner
from pg_type t join pg_namespace n on n.oid = t.typnamespace
where n.nspname = 'public' order by t.typname;
select e.extname, e.extversion, e.extrelocatable, n.nspname as extension_schema,
       pg_get_userbyid(e.extowner) as extension_owner
from pg_extension e join pg_namespace n on n.oid = e.extnamespace;
select n.nspname, c.relname as history_table
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relname in ('databasechangelog', 'databasechangeloglock')
order by n.nspname, c.relname;
rollback;
```

Also inspect `pg_operator`, `pg_opclass`, `pg_opfamily`, `pg_collation`, `pg_conversion`, and extension membership (`pg_depend`) for `public`. Record owner and extension membership. Confirm `vector.extrelocatable`, current extension schema/version, and migration-principal ability to relocate it. Rerun the approved exporter; hashes stay immutable and any mismatch remains a data-load hard stop.

**Actions Applied**
- Disposable approved-archive restore inventory found no public relations or Liquibase history. Public contains only relocatable `vector` 0.8.7 and `pg_trgm` 1.6 extensions and their members (234 and 46 respectively); both are owned by `ford_catalog_admin`. No pg_trgm use or trigram index exists in repository code or candidate catalog relations.
- Approved archive is unchanged; fail-closed exporter still rejects the same two approved hashes.

**Verification:** Both extensions report `extrelocatable = true`. No other public object classes were found outside extension members. Actual deployment migration-principal permissions remain unverified; DDL must fail closed if that principal cannot relocate either extension. Hash mismatch still blocks snapshot generation/load.

**Actions Applied**
- Read-only audit of the approved disposable restore found `vector` 0.8.7 and `pg_trgm` 1.6 as the only public objects; both are relocatable and owned by `ford_catalog_admin`.
- Confirmed no repo/candidate use of pg_trgm and no catalog trigram indexes. Archive and hashes remain unchanged.

**Verification**
- Both extension schemas and object inventory recorded; migration principal permission remains an explicit deployment prerequisite.

- [x] Add failing migration and clean-schema assertions

In `FordMetadataSchemaCutoverIntegrationTest`, add `movesPgvectorAndPgTrgmToFordCatalogAndLeavesPublicEmpty`: move both fixture extensions from `ford_catalog` to `public`, run the actual Liquibase changeset, then assert both extension schemas are `ford_catalog`, `to_regtype('ford_catalog.vector')` is non-null, `to_regtype('public.vector')` is null, and no user object or extension is present in `public`. In `SchemaIntegrationTest`, assert `ford_catalog.vector_cosine_ops` and `ford_catalog.gin_trgm_ops` exist, Liquibase tracking exists only in `ford_metadata`, and `ford_catalog_reader` still lacks metadata `USAGE` and table access. Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew integrationTest --tests '*FordMetadataSchemaCutoverIntegrationTest' \
  --tests '*SchemaIntegrationTest'
```

Observe the expected failure before implementing migration code.

**Verification:** Red tests fail on current public extension locations/references or the missing empty-public invariant, not on fixture setup.

**Actions Applied**
- Added schema integration assertions for extension namespaces, extension operator classes, Liquibase history in `ford_metadata`, and zero objects in `public`.
- Test fixture starts with extensions in `ford_catalog`; schema test moves pg_trgm to `public`, and 025 moves vector to `public` before the forward migration.

**Verification**
- Observed the new assertion fail against existing behavior (`vector` remained in `public`) before adding changeset 026.

- [x] Add a forward-only vector-extension changeset

Create a new changeset included after `025-ford-metadata-schema.yaml` and before catalog DDL. It creates `ford_catalog` and installs or relocates both `vector` and `pg_trgm` there. Use a loop over these fixed extension/schema pairs and safely quoted identifiers:

```sql
create schema if not exists ford_catalog;
do $$
declare
  extension_name text;
  extension_schema text;
  extension_relocatable boolean;
begin
  foreach extension_name in array array['vector', 'pg_trgm'] loop
    if not exists (select 1 from pg_extension where extname = extension_name) then
      execute format('create extension %I with schema ford_catalog', extension_name);
    else
      select n.nspname, e.extrelocatable
        into extension_schema, extension_relocatable
      from pg_extension e join pg_namespace n on n.oid = e.extnamespace
      where e.extname = extension_name;
      if extension_schema <> 'ford_catalog' then
        if not extension_relocatable then
          raise exception 'Extension % is not relocatable', extension_name;
        end if;
        execute format('alter extension %I set schema ford_catalog', extension_name);
      end if;
    end if;
  end loop;
end $$;
```

PostgreSQL enforces extension ownership on `ALTER EXTENSION`; propagate any permission failure and do not retry with superuser or destructive commands. Check every target's Liquibase history before touching `025`. Since `025` currently sends pgvector to `public`, edit it only if proven unapplied everywhere. If any target applied it, do not edit it or permit a migration sequence that puts either extension back in `public`; stop and request a reviewed migration redesign. Do not execute against production. Do not add pg_trgm to hybrid ranking or create trigram indexes.

**Actions Applied**
- Added `026-ford-extension-schemas.yaml` after 025 and before catalog DDL. Both extension names are safely quoted; non-relocatable and permission failures abort; migration fails closed if any object remains in `public`.
- Left 025 and earlier historical changesets unchanged.

**Verification:** Full Liquibase integration test passes with pgvector and pg_trgm ending in `ford_catalog`; clean `public` remains present and empty.

- [x] Replace public-qualified runtime vector references and public search paths

Use `ford_catalog.vector`, `OPERATOR(ford_catalog.<=>)`, and `ford_catalog.vector_cosine_ops` in new/unapplied catalog DDL and SQL. Keep applied changesets unchanged; unqualified vector declarations in applied changesets resolve through explicit `ford_metadata,ford_catalog` search paths. Remove `public` from app and catalog JDBC URLs. Put Liquibase history in `ford_metadata`; keep reader paths limited to catalog/metadata and reader access denied on metadata.

**Actions Applied**
- Qualified runtime vector casts and cosine operators with `ford_catalog`; removed `public` from application/catalog JDBC URLs and test search paths.
- Changed the catalog document embedding declaration to `ford_catalog.vector(1024)`. Kept already-existing migrations 017/025 intact.

**Verification:** Search finds `public.vector` only in historical 025 and the isolated legacy-cutover fixture; no runtime JDBC path contains `public`.

- [x] Align Testcontainers and integration fixtures with the approved schema layout

Initialize pgvector and pg_trgm in `ford_catalog`; use only `ford_metadata,ford_catalog` in JDBC search paths. Use 1,536D conversation vectors and 1,024D catalog vectors. Ensure test migrations initialize Liquibase history in `ford_metadata`. Remove test-only application objects/extensions from `public`; retain public installation only in the isolated relocation fixture.

**Verification:** Catalog, schema, conversation-memory, and pgvector integration tests pass and assert extension placement and metadata-access boundaries.

**Actions Applied**
- Testcontainers now creates `ford_metadata` and installs vector/pg_trgm in `ford_catalog`; catalog fixture tolerates the pre-created schema.
- Legacy cutover fixture moves extensions to `public` only to exercise relocation history.

**Verification**
- Focused schema/catalog/conversation/vector integration set passed.

- [x] Verify all public objects are reviewed and no drop/cascade was introduced

Run the full PostgreSQL integration suite and query `pg_catalog` to assert no user-owned relation, routine, type, operator, operator class, extension, or Liquibase history object remains in `public`. Confirm the built-in schema remains present. Inspect diffs for `DROP SCHEMA`, `CASCADE`, archive/hash edits, and unrelated deployment changes.

**Verification:** Full integration suite passes; public-schema inventory is empty of app/extension objects; no destructive DDL or snapshot changes are present.

**Actions Applied**
- Changeset 026 checks eight public object catalogs and fails if any public object remains after extension relocation.

**Verification**
- Full integration suite passed; schema integration test verified built-in `public` exists with zero inventoried objects/extensions.

## Task 7: Full verification and handoff

**Files:** Reviewed catalog search and no-public schema-placement files from Tasks 1–6; no snapshot data resource and no unrelated AI policy changes.

**Consumes:** Completed tasks 1–6.

**Produces:** Verified model-level catalog search and schema placement with all rollout gates still closed.

- [x] Run unit tests

From `ford-ai-service`, run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock ./gradlew test
```

Expected: BUILD SUCCESSFUL.

**Actions Applied**
- Ran full Gradle unit suite after search/tool changes.

**Verification**
- `BUILD SUCCESSFUL`.

- [x] Run catalog and schema integration tests

Run:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock \
  ./gradlew integrationTest \
  --tests '*JdbcFordCatalogReaderIntegrationTest' \
  --tests '*SchemaIntegrationTest'
```

Expected: selected tests pass. Run all integration tests as the separate gate below.

**Actions Applied**
- Ran catalog reader and schema integration classes only.

**Verification**
- Before the no-public changes, fresh full integration passed 87 tests and fresh full unit tests passed. Rerun after Task 6; previous `public`-based test setup is not accepted as final verification.

- [x] Run script tests and whitespace validation

Run:

```bash
python3 -m unittest discover -s scripts/tests -v
git diff --check
```

Expected: all Python tests pass and `git diff --check` emits no errors.

**Actions Applied**
- Ran Python script suite and tracked/untracked whitespace checks.

**Verification**
- 48 Python tests pass; `git diff --check` clean.

- [x] Verify no forbidden rollout or snapshot changes

Check `git status --short` and the feature flag. Confirm no archive/manifest hash changes, no snapshot data changeset, no deployment/routing/scraper edits, and `FORD_CATALOG_TOOLS_ENABLED` remains false by default.

**Actions Applied**
- Left deployment, routing, scraper, snapshot archive/manifest, and Liquibase data resources unchanged. Catalog enabled flag not changed.

**Verification**
- `application.yml` still defaults `FORD_CATALOG_TOOLS_ENABLED:false`; exporter still refuses on the same two manifest hashes.

- [x] Run the complete unit and integration suites after the no-public change

Run from `ford-ai-service`, in order:

```bash
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock ./gradlew test --rerun-tasks
DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/testbox/docker.sock ./gradlew integrationTest --rerun-tasks
python3 -m unittest discover -s scripts/tests -v
git diff --check
```

Expected: each command exits `0`; integration output reports all tests passed; unit output reports `BUILD SUCCESSFUL`; script suite reports no failures.

**Actions Applied**
- Ran all commands freshly after the schema changes.

**Verification**
- Unit tests: `BUILD SUCCESSFUL` (663 tests).
- Integration tests: `BUILD SUCCESSFUL`.
- Python script tests: 48 passed; `git diff --check` clean.

- [ ] Commit reviewed catalog search and no-public files only

Stage exact catalog search, extension-placement, and required no-public schema/test files from Tasks 2–6. Use `git add -p` for shared files (`application.yml`, `SpringAiConfiguration.java`, `db.changelog-master.yaml`, and test files) so unrelated dirty changes stay unstaged. Exclude unrelated AI policy, prompt, handoff, routing, and deployment files. Review the staged path list before committing:

```bash
git diff --cached --name-only
git diff --cached --check
git commit -m "feat: keep Ford catalog and pgvector out of public"
```

Do not merge until staged paths contain only approved scope and all gates pass. Do not deploy or enable catalog tools.

**Actions Applied**
- No code commit made. Catalog implementation files and many unrelated workspace changes were already uncommitted; do not stage them broadly.

**Verification**
- Feature branch and existing worktree remain intact for the user's branch-handling choice.

## Stop Conditions

- Approved snapshot document/model/year/variant identity is missing or ambiguous.
- Any approved manifest hash remains unexplained before snapshot export/load.
- Search requires fuzzy model identity, semantic substitution for exact facts, or a second database read to return trim details.
- Reader can write or read metadata, capture, or provenance data.
- Any unreviewed application-owned object remains in `public`, or `public` would need to be dropped/cascaded.
- Catalog flag, release gates, scraper behavior, routing, or deployment would need to change.
