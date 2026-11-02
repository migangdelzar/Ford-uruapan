# Ford Service Catalog Reader Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor Ford Service tools so user/chat/lead requirements stay in `ford_metadata`, while canonical Ford facts and hybrid-search documents come from `ford_catalog` in database `ford`.

**Architecture:** Keep bot/Meta sessions, leads, and user requirements (intent, warmth, budget, model interests, notes) in `ford_metadata`. Read canonical Ford models/specs/prices/offers/promotions plus catalog documents/embeddings from a dedicated `ford_catalog_reader` datasource. Use deterministic SQL for exact facts and keyword+vector retrieval with reciprocal-rank fusion for qualitative use cases. Refactor or retire the ambiguous `vehicle_snapshot_search` adapter/name. No stock feed exists: stock/availability questions must use advisor handoff, never stale database claims.

**Tech Stack:** Java, Spring Boot, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL/pgvector, JUnit 5, AssertJ, Testcontainers, OCI Vault.

## Global Constraints

- Both datasources connect to the single database `ford`; primary datasource schema is `ford_metadata`, catalog datasource schema is `ford_catalog`.
- Catalog datasource username is exactly `ford_catalog_reader`; it must not read private capture/provenance tables or write.
- The approved source currently grants the reader SELECT on `ford_read.document_provenance_v1`; the target must revoke that privilege and prove the read is denied.
- Bot/Meta sessions, leads, and captured user requirements stay in `ford_metadata`.
- Canonical Ford models/specs/prices/offers/promotions come from `ford_catalog`.
- User-entered budget and model interests are requirements in `ford_metadata`, not catalog facts.
- Ford catalog documents and their 1,024-dimensional `bge-m3` embeddings live in `ford_catalog`; conversational-memory embeddings remain in `ford_metadata`.
- Existing tenant-scoped approved knowledge and `public.vector_store` embeddings stay in `ford_metadata`; only scraper-owned Ford catalog documents/vectors move to `ford_catalog`.
- Catalog embeddings must be queried with a compatible `bge-m3` model and dimension; do not reuse incompatible `nomic-embed-text` conversation vectors.
- No trusted stock feed exists. Stock questions route to human advisor handoff; no availability claims.
- Catalog tools default disabled; no user-facing catalog invocation until checks pass.
- Existing protected release workflows remain unchanged; no gate bypass or routing change.
- The candidate snapshot has known coverage/price gaps, including in production per the approved design; report this and do not claim production validation.
- Preserve unrelated workspace modifications.

## File Map

- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceProperties.java` — validate URL, exact reader username, and read-only feature configuration.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java` — named catalog `DataSource` and `JdbcClient`, separate from primary datasource.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogReader.java` — parameterized deterministic queries explicitly qualified to `ford_catalog`.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogKnowledgeReader.java` — keyword/semantic retrieval over catalog documents, merged through reciprocal-rank fusion with source URLs preserved.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/domain/CatalogFilters.java`, `CatalogFact.java`, and `CatalogDocumentHit.java` — typed query/result contracts for exact facts and hybrid document hits.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/domain/CatalogQueryEmbeddingModel.java` and `adapter/out/OllamaCatalogQueryEmbeddingModel.java` — separate query embedding port pinned to compatible `bge-m3` 1,024-dimensional embeddings; do not change conversation-memory embedding configuration.
- Modify: `ford-ai-service/src/main/resources/application.yml` — datasource URL/user/secret and `FORD_CATALOG_TOOLS_ENABLED=false` defaults.
- Create: unit tests under `ford-ai-service/src/test/java/com/ford/service/catalog/adapter/out/` and PostgreSQL integration test under `src/integrationTest/java/com/ford/service/catalog/`.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java` and related tool policy/tests — replace the confusing `vehicle_snapshot_search` catalog path with clear Ford catalog tools; preserve tools that capture user/lead requirements.
- Create: local catalog tool class under `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/` — expose Ford model/spec/price/offer/promotion reads using the catalog reader.

## Task 1: Pin datasource configuration contract

**Interfaces:** Catalog datasource URL is `jdbc:postgresql://${FORD_DATA_HOST}:5432/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`; user is `ford_catalog_reader`; password comes only from the existing Vault secret mapping; flag defaults to false. Catalog query embeddings use a separately named `CatalogQueryEmbeddingModel` configured for `bge-m3` and dimension `1024`, matching all source `catalog_documents_v1.embedding` rows. The global conversation embedding model is unchanged.

- [x] Add unit tests for default-disabled behavior, exact database/schema URL, exact username, missing secret OCID rejection when enabled, and rejection of a writer/admin username.
- [x] Run `./gradlew test --tests '*CatalogDataSourcePropertiesTest'` from `ford-ai-service`; expected: fail because the dedicated properties do not exist.
- [x] Add datasource properties and fail-closed validation. Never log URL passwords or resolved Vault values.
- [x] Rerun focused tests; expected: all pass and no default enables catalog tools.

**Verification:** Unit tests prove safe defaults and fail-closed config.

## Task 2: Add a separately qualified read-only JDBC client

**Interfaces:** Bean names are `catalogDataSource` and `catalogJdbcClient`. Existing primary `JdbcClient` remains the default injection for current repositories.

- [x] Add a Spring context test that injects both clients and proves the existing repository receives the primary datasource while the new reader receives `catalogJdbcClient`.
- [x] Run focused test; expected: failure because no named catalog beans exist.
- [x] Implement the named datasource and client without changing the primary `DataSource` or Liquibase's primary connection.
- [x] Run focused tests and `./gradlew test`; expected: all pass.

**Verification:** Spring context starts with catalog off and does not require catalog secrets or network access in local default mode.

## Task 3: Implement contract-tested catalog queries

**Interfaces:**

```java
public record CatalogFilters(
        String model,
        Integer modelYear,
        BigDecimal minimumPrice,
        BigDecimal maximumPrice,
        String color,
        String category,
        String bodyType,
        String fuelType) {}

public record CatalogFact(
        UUID modelYearId,
        UUID variantId,
        String model,
        Integer modelYear,
        String variant,
        BigDecimal price,
        String currency,
        String color,
        String sourceUrl) {}

public record CatalogDocumentHit(
        UUID documentId,
        String documentType,
        String title,
        String semanticContent,
        UUID modelYearId,
        UUID variantId,
        UUID offerId,
        String sourceUrl) {}
```

`JdbcFordCatalogReader` exposes `searchStructuredCatalog(CatalogFilters filters, int limit)`, `getCatalogVariant(UUID variantId)`, and `listCurrentOffers(UUID modelYearId)`. `JdbcFordCatalogKnowledgeReader` exposes `searchHybrid(String text, CatalogFilters filters, int limit)` and combines keyword and semantic results with the existing `ReciprocalRankFusion`. Exact price/color/model/year predicates use the structured tool and SQL; qualitative queries (family/work use) use keyword plus vector retrieval over `catalog_documents_v1`. Both adapters preserve `source_url`, bind user values, and use only the catalog reader datasource. `CatalogQueryEmbeddingModel.embed(String text)` returns `float[1024]` from `bge-m3` and rejects any other dimension or non-finite value before SQL execution.

- [x] Add Testcontainers PostgreSQL integration tests with minimal `ford_catalog` fixtures for exact model/year/price/color lookup, trim facts, current offers, and empty results.
- [x] Add keyword-only, semantic-only, and overlap retrieval fixtures; verify returned hits are deduplicated, source citations survive, and the existing reciprocal-rank-fusion unit test ranks overlap hits first.
- [x] Run focused integration test; expected: fail because reader methods/schema fixtures do not exist.
- [x] Implement both adapters with bounded results, explicit mapping, compatible 1,024-dimensional `bge-m3` vectors, and parameter-bound SQL.
- [x] Add reader-role permission tests proving approved catalog SELECT succeeds while writes, private catalog capture SELECT, and `ford_metadata` SELECT fail.
- [x] Run `./gradlew integrationTest --tests '*JdbcFordCatalogReaderIntegrationTest'`; expected: all contract and privilege tests pass.

**Verification:** Integration tests prove the adapter uses `ford_catalog`, never accesses Service metadata, and works with a read-only database identity.

## Task 4: Gate tools and verify behavior

- [x] Add tests that catalog tool registration is absent by default and visible only when explicitly enabled with both reader adapters.
- [x] Run focused catalog tests; confirmed failure before configuration class existed, then verified after wiring.
- [ ] Wire chat/lead/user-requirement flows to `ford_metadata` and canonical Ford model/spec/price/offer/promotion/hybrid-document tools only to the catalog reader adapters; retire the old Service catalog/knowledge reader paths after catalog data is migrated and verified.
- [ ] Route stock/availability questions through a behavior-level synthetic test of authorized advisor handoff; prompt now explicitly forbids stock claims and preserves the app's consent gate. Do not query `vehicle_unit` or make availability claims until a trusted stock feed exists.
- [x] Update Spring AI tool registration, quality/decision policies, prompt references, and tests for the new catalog tools. Keep the legacy `vehicle_snapshot_search` path until verified data cutover; it is not safe to remove yet.
- [x] Run `./gradlew test`; all unit tests pass.
- [ ] Run `./gradlew integrationTest`; catalog/metadata focused tests pass, but the full suite still has four unrelated `LeadJourneyRepositoryIntegrationTest` missing-table failures under the test profile.
- [ ] Keep flag false for all database and app cutover steps. Enable only after release approvals, Vault lookup, role probes, health checks, and synthetic exact-model/trim/offer/citation/price/no-match checks pass.

**Verification:** Synthetic checks use no customer/conversation records; reader remains read-only and Service metadata remains unchanged.

## Stop Conditions

- Schema plan has not produced `ford_catalog` in a verified `ford` database.
- Reader role can write, access `ford_metadata`, or select private capture/provenance relations.
- Catalog tool refactor loses chat/lead/user-requirement data or leaves canonical catalog facts split across competing sources.
- Vault secret mapping, test results, artifact identity, or protected approval is missing.
