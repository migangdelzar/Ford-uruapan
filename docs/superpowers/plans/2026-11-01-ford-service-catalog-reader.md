# Ford Service Catalog Reader Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor Ford Service vehicle tools so user/chat/lead requirements stay in `ford_metadata` and canonical Ford catalog data comes from `ford_catalog` in database `ford`.

**Architecture:** Keep bot/Meta sessions, leads, and user requirements (intent, warmth, budget, model interests, notes) in `ford_metadata`. Read canonical Ford models/specs/prices/offers/promotions from a dedicated `ford_catalog_reader` datasource. Refactor or retire the ambiguous `vehicle_snapshot_search` adapter/name; never store canonical catalog facts as conversational requirements. The catalog tool flag stays off until schema, ACL, health, and synthetic checks pass.

**Tech Stack:** Java, Spring Boot, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL/pgvector, JUnit 5, AssertJ, Testcontainers, OCI Vault.

## Global Constraints

- Both datasources connect to the single database `ford`; primary datasource schema is `ford_metadata`, catalog datasource schema is `ford_catalog`.
- Catalog datasource username is exactly `ford_catalog_reader`; it must not read private capture/provenance tables or write.
- Bot/Meta sessions, leads, and captured user requirements stay in `ford_metadata`.
- Canonical Ford models/specs/prices/offers/promotions come from `ford_catalog`.
- User-entered budget and model interests are requirements in `ford_metadata`, not catalog facts.
- Catalog tools default disabled; no user-facing catalog invocation until checks pass.
- Existing protected release workflows remain unchanged; no gate bypass or routing change.
- The candidate snapshot has known coverage/price gaps, including in production per the approved design; report this and do not claim production validation.
- Preserve unrelated workspace modifications.

## File Map

- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceProperties.java` — validate URL, exact reader username, and read-only feature configuration.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java` — named catalog `DataSource` and `JdbcClient`, separate from primary datasource.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcFordCatalogReader.java` — queries explicitly qualified `ford_catalog` relations.
- Modify: `ford-ai-service/src/main/resources/application.yml` — datasource URL/user/secret and `FORD_CATALOG_TOOLS_ENABLED=false` defaults.
- Create: unit tests under `ford-ai-service/src/test/java/com/ford/service/catalog/adapter/out/` and PostgreSQL integration test under `src/integrationTest/java/com/ford/service/catalog/`.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java` and related tool policy/tests — replace the confusing `vehicle_snapshot_search` catalog path with clear Ford catalog tools; preserve tools that capture user/lead requirements.
- Create: local catalog tool class under `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/` — expose Ford model/spec/price/offer/promotion reads using the catalog reader.

## Task 1: Pin datasource configuration contract

**Interfaces:** Catalog datasource URL is `jdbc:postgresql://${FORD_DATA_HOST}:5432/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`; user is `ford_catalog_reader`; password comes only from the existing Vault secret mapping; flag defaults to false.

- [ ] Add unit tests for default-disabled behavior, exact database/schema URL, exact username, missing secret OCID rejection when enabled, and rejection of a writer/admin username.
- [ ] Run `./gradlew test --tests '*CatalogDataSourcePropertiesTest'` from `ford-ai-service`; expected: fail because the dedicated properties do not exist.
- [ ] Add immutable properties and validation. Never log URL passwords or resolved Vault values.
- [ ] Rerun focused tests; expected: all pass and no default enables catalog tools.

**Verification:** Unit tests prove safe defaults and fail-closed config.

## Task 2: Add a separately qualified read-only JDBC client

**Interfaces:** Bean names are `catalogDataSource` and `catalogJdbcClient`. Existing primary `JdbcClient` remains the default injection for current repositories.

- [ ] Add a Spring context test that injects both clients and proves the existing repository receives the primary datasource while the new reader receives `catalogJdbcClient`.
- [ ] Run focused test; expected: failure because no named catalog beans exist.
- [ ] Implement the named datasource and client without changing the primary `DataSource` or Liquibase's primary connection.
- [ ] Run focused tests and `./gradlew test`; expected: all pass.

**Verification:** Spring context starts with catalog off and does not require catalog secrets or network access in local default mode.

## Task 3: Implement contract-tested catalog queries

**Interfaces:** `JdbcFordCatalogReader` exposes `searchCatalog(String text, Integer year, int limit)`, `getCatalogVariant(UUID variantId)`, `listCurrentOffers(UUID modelYearId)`, and `getCatalogStatus()`. Chat/lead/user-requirement flows remain in `ford_metadata`; canonical Ford model/spec/price/offer/promotion reads use this adapter. Queries explicitly use `ford_catalog` and bind all user values.

- [ ] Add Testcontainers PostgreSQL integration tests with minimal `ford_catalog` fixtures for exact model/year lookup, trim facts, offers/promotions, and empty results.
- [ ] Run focused integration test; expected: fail because reader methods/schema fixtures do not exist.
- [ ] Implement the query adapter with bounded limits, explicit result mapping, and no SQL concatenation from caller input.
- [ ] Add reader permission tests proving approved catalog SELECT succeeds and INSERT/UPDATE/DELETE/TRUNCATE, capture table SELECT, and `ford_metadata` SELECT fail.
- [ ] Run `./gradlew integrationTest --tests '*JdbcFordCatalogReaderIntegrationTest'`; expected: all contract and privilege tests pass.

**Verification:** Integration tests prove the adapter uses `ford_catalog`, never accesses Service metadata, and works with a read-only database identity.

## Task 4: Gate tools and verify behavior

- [ ] Add tests that the catalog tool registration is absent/disabled when `FORD_CATALOG_TOOLS_ENABLED=false` and visible only when explicitly enabled with valid reader config.
- [ ] Run focused catalog tests and confirm expected red before wiring.
- [ ] Wire chat/lead/user-requirement flows to `ford_metadata` and canonical Ford model/spec/price/offer/promotion tools only to `JdbcFordCatalogReader`; retire the old Service catalog repository path after its catalog data is migrated and verified.
- [ ] Update tool registrations, quality/decision policies, prompt references, and tests to remove the ambiguous `vehicle_snapshot_search` name after the replacement tool is wired.
- [ ] Run `./gradlew test` then `./gradlew integrationTest`; expected: all pass before image build or E2E.
- [ ] Keep flag false for all database and app cutover steps. Enable only after release approvals, Vault lookup, role probes, health checks, and synthetic exact-model/trim/offer/citation/price/no-match checks pass.

**Verification:** Synthetic checks use no customer/conversation records; reader remains read-only and Service metadata remains unchanged.

## Stop Conditions

- Schema plan has not produced `ford_catalog` in a verified `ford` database.
- Reader role can write, access `ford_metadata`, or select private capture/provenance relations.
- Catalog tool refactor loses chat/lead/user-requirement data or leaves canonical catalog facts split across competing sources.
- Vault secret mapping, test results, artifact identity, or protected approval is missing.
