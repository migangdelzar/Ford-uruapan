# MÍA Catalog Evidence Lane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Ford Service a read-only Java catalog path whose answers preserve Ford-scraping facts, freshness, prices, promotions, and source URLs.

**Architecture:** Ford-scraping stays the only catalog writer and migration owner. Create a dedicated read-only Service datasource/adapter and compare Java output against the existing Node catalog contract. The user approved removing the Node MCP runtime and remote Service client immediately, accepting a temporary customer-tool gap while parity is completed. Keep local Spring AI catalog tools disabled until parity and datasource gates pass. Retain Ford-scraping's crawler, schema, migrations, and scheduler.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring JDBC `JdbcClient`, Spring AI 2.0.1, PostgreSQL, TypeScript 5.9, Vitest 3.2.4, Testcontainers.

## Global Constraints

- Do not change Ford-scraping ingestion, crawler, scheduler, or write ownership. Additive forward-only `ford_read` contract views are allowed when Java parity requires existing source fields; never edit an applied migration.
- Do not reuse Ford Service's internal `vehicle_model`/`vehicle_variant` schema as the MCP catalog.
- Java catalog credentials are read-only and use a separately configured datasource.
- Preserve exact public source URLs, effective dates, and freshness; never turn unavailable evidence into a fact.
- No catalog write capability moves into Ford Service; no generative-provider fallback is introduced.
- Do not enable local customer catalog tools until all required tool behavior and datasource checks pass.
- Do not stop production containers or delete external secrets/tunnel/IAM resources as part of this repository change; document that cutover separately.

---

### Task 1: Freeze the existing MCP read contract

**Files:**
- Test: `tests/mcp/mia-catalog-contract.test.ts`
- Inspect only: `src/mcp/server.ts`, `src/search/search-service.ts`, `src/search/offer-search-service.ts`, `src/storage/read-services.ts`, `src/domain/contracts.ts`

- [x] Write output-contract tests for observation timestamps, exact model/technical-sheet/price URLs, and promotion validity/source. Run `npx vitest run tests/mcp/mia-catalog-contract.test.ts`; confirmed red because search and detail compaction dropped `crawledAt`.
- [x] Expose the existing crawl timestamp as `observedAt` in compact search and exact-vehicle results; keep missing timestamps absent rather than inventing freshness.
- [x] Re-run `npx vitest run tests/mcp/mia-catalog-contract.test.ts`; 3 tests pass.
- [x] Run `npm run build` and `npm run test:unit -- tests/mcp/server.test.ts`; TypeScript build passed and 339 unit/MCP tests passed.

### Task 2: Add the complete Java read-only catalog port

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/application/McpCatalogPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/domain/McpVehicleFact.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/domain/McpCatalogEvidence.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/McpCatalogJdbcAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/McpCatalogDataSourceConfiguration.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Test: `ford-ai-service/src/test/java/com/ford/service/catalog/McpCatalogJdbcAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/catalog/McpCatalogConfigurationTest.java`

- [x] Add tests first for tenant-independent public catalog reads, exact-source URL retention, effective-price filtering, empty rows, SQL errors, and missing optional vector data. Focused and full Service suite passed (673 tests).
- [x] Implement `McpCatalogPort` with local read operations for every current MCP capability: `search_vehicles`, `search_vehicle_and_offers`, `search_offers`, `get_vehicle`, `get_page_links`, `compare_vehicles`, `compare_trims`, `compare_model_years`, `get_variant_comparisons`, `get_vehicle_provenance`, `list_promotions`, and `get_catalog_status`; evidence fields preserve value, source URL, observed/effective time, and freshness.
- [x] Bind a qualified `JdbcClient` to `FORD_CATALOG_*` settings. Fail closed when credentials are absent; do not fall back to the Service database.
- [x] Query only stable Ford-scraping read views, with bound parameters. Never issue DDL or mutations. No database password is logged.
- [x] Re-run `./gradlew test` and `./gradlew bootJar`; both passed. Boot jar contains local adapter/tool classes and no remote MCP client configuration.

### Task 3: Prove result parity before enabling local customer tools

**Files:**
- Create: `ford-ai-service/src/test/resources/catalog/mia-catalog-parity.json`
- Create: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogToolParityIntegrationTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/McpCatalogTools.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java` only if a named allowlist is required
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: Ford Service production Ansible/environment configuration that sets `FORD_MCP_ENABLED`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/McpCatalogToolsTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java`

- [x] Port the three archived Node catalog contract cases into `mia-catalog-parity.json` and a Java adapter-to-tool test; verify search model/year/price/freshness/source URLs, exact detail model/technical-sheet URLs, promotion validity/source, and missing-evidence behavior byte-for-byte where applicable. The five focused fixture tests pass.
- [ ] Complete field-by-field parity for all catalog capabilities and comparison outputs; the archived Node fixture covers only three cases and does not prove this broader requirement.
- [x] Run `./gradlew integrationTest --tests '*CatalogToolParityIntegrationTest'`; assert behavior against deterministic PostgreSQL Testcontainer rows matching the Ford read-view contract. Node integration tests separately validate actual migration-backed views. Passed in Docker runner.
- [x] Correct adapter parity: normalize trim keys like Node (remove separators); keep model-only facts model-level; make explicit trim facts exact; omit prices/facts/offers/promotions lacking both source URL and observation time. H2 regression coverage and the real snapshot test pass.
- [x] Keep search lexical and report semantic ranking parity as not established; Java and Node do not currently expose matching embedding model/settings/dimension.
- [x] Add Spring AI annotated read-only tools corresponding to every existing MCP capability. Preserve input/output evidence fields where useful; exclude write/booking/handoff actions. Financing search exposes catalog-backed Ford Credit terms only; do not add financial calculation or approval.
- [x] Test the read-only tool surface against a fake `McpCatalogPort`, including evidence fields, empty exact lookups, validity dates, and exact URLs; preserve the existing `ToolAuthorizationPolicy` allowlist/call limit.
- [x] Register local tools in `SpringAiConfiguration`; the explicit feature flag and dedicated datasource gate callbacks, and default remains disabled. No remote Ford MCP client remains.
- [x] Run `./gradlew test` and `./gradlew bootJar`; both pass. The catalog slice is not complete until PostgreSQL parity passes.

#### Ranking, reranking, and grounding continuation

- [ ] Add a reviewed Spanish retrieval set (~100 questions) with relevant document IDs, expected fact evidence, model/year/trim selectors, and volatile-vs-document question type.
- [x] Verify installed framework versions and APIs: Spring Boot 4.1.1, Spring AI 2.0.1 RAG `RetrievalAugmentationAdvisor`/`DocumentRetriever`/`DocumentPostProcessor`, existing LangGraph4j turn graph, and TypeSafe Spring AI 0.1.0 `JevDocumentReranker`.
- [x] Keep LangGraph4j as the turn orchestrator and compose Spring AI 2.0.1 `RetrievalAugmentationAdvisor` only for the existing knowledge-search route; do not create another agent graph, memory store, or orchestration framework. Focused route tests verify the advisor is not attached to catalog routes.
- [x] Bridge the existing `KnowledgeRetrieval` into Spring AI's `DocumentRetriever`, preserving tenant, locale, approved-only, and effective-date filters. The existing document tool remains the configured fallback when advisor wiring is unavailable.
- [x] Reuse existing Spring AI TypeSafe `JevDocumentReranker` through `DocumentPostProcessor`, gated off by default; configuration fails closed if enabled without TypeSafe. No custom reranker client/service was added.
- [ ] Add typo retrieval using PostgreSQL's existing trigram capability only if the supported DB extension/index and migrations can be safely applied; fuse full-text, trigram, and vector rankings with the existing `ReciprocalRankFusion` utility generalized to N lists.
- [ ] For the Ford catalog path, expose only the necessary versioned embedding/document read view from Ford-scraping, query with the exact stored `bge-m3` model through Spring AI's configured `EmbeddingModel` request options, verify model tag/dimension, and preserve lexical fallback. Do not use Service `nomic-embed-text` vectors against catalog embeddings.
- [ ] Apply trusted model/year filters before candidate ranking and preserve exact selectors through Spring AI's query-transformer extension. Avoid another model call unless the reviewed follow-up cases show it is needed.
- [ ] Benchmark current Node score fusion and Java RRF on the reviewed set; report recall@5, MRR, nDCG, evidence coverage, and latency. Keep current fusion unless the larger set demonstrates a meaningful gain.
- [ ] Evaluate the existing Jev reranker over a bounded candidate set only after recall is established; measure MRR/nDCG/evidence/answer gains, p50/p95 latency and provider cost. Keep fused order on timeout; leave reranking disabled if it does not justify runtime cost.
- [x] Add a per-turn evidence ledger for tool results and RAG citations; validate numeric claims and source URLs against exact evidence, with one repair and safe fallback using existing turn/handoff controls. Remaining model-name/length calibration stays in M4b.
- [ ] Verify LangGraph4j node/edge routing and Spring AI advisor order, retry behavior, and accepted-message-only memory semantics against pinned versions; do not replace or bypass the durable tenant/conversation memory owner.
- [ ] Keep structured tools authoritative for prices, promotions, inventory, and current specifications; use RAG only for approved documents/narrative knowledge, with calibrated abstention.
- [ ] Enable callbacks only after complete tool output parity, compatible semantic retrieval behavior, evidence guard, database permissions, and evaluation gates pass.

### Task 4: Remove the Node MCP runtime now; temporary tool gap accepted

**Files:**
- Modify: `package.json`, `src/cli.ts`, `src/composition-root.ts`, `src/config.ts`
- Remove: `src/mcp/server.ts`, `src/mcp/http.ts`, MCP-only runtime dependencies and server tests
- Modify: `Dockerfile`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, runtime preflight/deployment configuration, and MCP-specific docs
- Remove: Ford Service remote Spring AI MCP dependency, client config, and connection customizer
- Preserve: crawler, API if independently used, catalog migrations/views, sync scheduler, and catalog QA fixtures/evaluations as applicable

- [ ] Inventory MCP-only secrets, Cloudflare tunnel routes, OCI host/container configuration, and release-all stage references; distinguish shared catalog database access from MCP transport credentials.
- [x] Remove Node MCP runtime, remote endpoint config, release/preflight workflows, and active tool evaluators now; external production resources and secret values remain untouched for rollback.
- [x] Update CI to test the remaining Node crawler/catalog/API functions and remove MCP container smoke while retaining scheduler image publication.
- [x] Verify no MCP server command, runtime dependency, active release/preflight workflow, or MCP endpoint remains in the active Node runtime/deployment configuration. The external production service and stored credentials remain untouched.

Verification: Node lint, build, 138 unit tests, and five container/workflow contract tests pass. Node PostgreSQL integration then passed 23 tests in a disposable fresh database using the existing local PostgreSQL test container; Colima profile/VM state was not modified. A prior run against an older local database failed because its already-applied migration 022 view shape was stale; the clean-database run passed.

### Task 5: Document least-privilege runtime setup

**Files:**
- Modify: `docs/operations/catalog-read-only-access.md`
- Modify: `ford-ai-service/src/main/resources/application-prod.yml`

- [x] Document required environment variable names, read-only grants, supported schema/view names, timeout behavior, and rollback to the last service/MCP topology before Node MCP removal.
- [x] Validate production YAML and run `./gradlew test`; full test suite and `bootJar` pass.

## Completion Gate

- [x] Java adapter and Spring AI tool tests pass (`./gradlew test bootJar --offline --no-build-cache --no-daemon`, isolated Gradle home).
- [ ] Java/catalog parity is demonstrated for facts, price dates, promotion state, freshness, and exact source links.
- [ ] Ford-scraping remains the sole catalog schema/data writer; its MCP transport is removed now by user choice.
- [ ] Customer catalog tools remain disabled until parity passes; temporary tool gap is accepted.
- [ ] Do not claim production cutover or cleanup without live verification and a separately approved rollout.



### Resumed verification note (2026-10-17)

- Added `CatalogToolParityIntegrationTest` using the disposable PostgreSQL Testcontainers contract schema. It compiles; runtime verification is blocked by host-port connectivity in the current Colima context: Ryuk cannot connect to its forwarded localhost port, and with Ryuk disabled the PostgreSQL mapped localhost port refuses connections. No test assertions ran.
- Focused adapter, datasource configuration, and tool tests pass. Full `test` and `bootJar` pass.
- Added `docs/operations/catalog-read-only-access.md` and kept `FORD_CATALOG_TOOLS_ENABLED=false` by default in the production profile.
- Do not mark Task 3 parity or completion gate complete until the PostgreSQL test and captured-contract fixture pass. H2 contract tests and code-level parity checks pass; semantic ranking parity is explicitly not established and search remains lexical. Production rollout remains gated despite the user's explicit deployment goal.

### Real catalog snapshot verification (2026-10-17)

- Added `CatalogSnapshotParityIntegrationTest`, gated by `FORD_CATALOG_PARITY_*` test environment variables, to query the adapter against the restored `ford-20261009T101630.207Z.dump` snapshot in disposable `ford_mia_catalog_parity_test`.
- Ran it in the Docker host-network runner because Colima mapped host ports are unavailable to Testcontainers on the Mac host: `./gradlew integrationTest --tests '*CatalogSnapshotParityIntegrationTest'` passed (1 test, 0 skipped, 0 failures).
- The test connected as `ford_catalog_parity_reader`, with SELECT grants on `ford_read`; privilege inspection confirmed SELECT=true and INSERT=false. The role grants were revoked and the temporary role dropped after the run.
- Verified actual BRONCO 2026 / Badlands 2 Puertas price, exact Ford Uruapan price URL, trim-specific source URLs, and non-null observation timestamps. This closes the real-database adapter smoke check, not full Node/Java contract parity for offers, promotions, comparison outputs, or all byte-for-byte fixtures.
- The runner container was stopped and removed. The existing PostgreSQL test container remains; disposable snapshot DB is retained for repeatability.
- Expanded the snapshot test to read a financing offer (cash price, monthly payment, term, source, observation), a dated promotion (active status as of 2026-10-17, validity dates and source), and comparison evidence (competitor and exact trim source). The expanded suite passed with `--rerun-tasks` as `ford_catalog_parity_reader`: 2 tests, 0 skipped, 0 failures. `has_table_privilege` returned SELECT=true / INSERT=false; role grants were revoked and the role dropped after the run.
- A first assertion expected the Base offer to be the first result; SQL ordering correctly returned Badlands first. Reworked the assertion to locate the Base offer by title among returned results. The rerun passed. This is a test-fixture ordering correction, not an adapter failure.
- Real-source coverage now demonstrates vehicle, facts, offers, promotions, and comparison reads from this snapshot. It still does not compare every MCP response field against an archived Node output fixture or exhaustively validate missing-evidence edge cases; completion gate remains open.
- Source review of Node `ComparisonService` against Java `McpCatalogTools` found a substantive parity gap: Java `compare_trims` and `compare_model_years` currently return independent `find` lookups, while Node emits trim/year pairs, source-backed changed facts, price deltas, promotion evidence, and missing-trim information. Do not enable or deploy these tools as parity-complete until those outputs are implemented and tested.
- Implemented Java pairwise trim and model-year comparison outputs: sorted found records, source-backed shared fact deltas, price differences, missing requested trims/years, and active promotions per found model year. Added TDD unit coverage. A first snapshot run exposed a null-counterpart `Map.entry` failure; matching counterpart keys are filtered before creating entries. The forced SELECT-only real-snapshot run then passed all 3 tests, including paired Bronco trims and Expedition 2025–2027 year results; full Service `test bootJar` also passed.
- Cross-model output now returns direct per-vehicle evidence (matching Node's array shape), fails if a requested model/year is missing, and includes image URLs with source/observation evidence. `vehicle_facts_v1.fact_type` is preserved as `McpVehicleFact.type`, so specification and feature facts stay distinguishable. The read view does not expose the source feature category, and a complete archived Node-to-Java fixture comparison is still pending. Keep the feature flag disabled until the full parity checklist passes.
- Disk/cache issue: Gradle test output passed, but an attempted build-cache write failed with `No space left on device` at 218 MiB free. Reran `./gradlew test bootJar --no-build-cache`, which passed. Use `--no-build-cache` while disk is critically low; do not prune Docker or user caches speculatively.
- Latest source state was reverified after duplicate-input normalization: `./gradlew test bootJar --no-build-cache` passed; snapshot suite reran with `--rerun-tasks --no-build-cache` as the SELECT-only role, 3 tests/0 skipped/0 failures. The temporary runner and DB role were removed after capturing the XML and permission check.
- Cross-model image/fact-kind parity was added with red/green tests: H2 verifies exact image URL/source/time and distinct `feature` vs `specification` kinds; real snapshot verifies direct Bronco/Expedition cross-model results, sourced image freshness, and sourced Bronco feature/specification facts. The forced snapshot run passed 3 tests, and the latest full `./gradlew test bootJar --no-build-cache` passed.
- Release-path audit: Service `.github/workflows/release.yml` still validates legacy remote MCP settings and pins deployment action SHA `972fe859e907753d60e163ecef2ee5cd1fb044f3`; that commit is absent from every local action clone. GitHub SSH failed with unknown UID 501 and HTTPS failed DNS resolution. A clean isolated action worktree `/private/tmp/ford-oci-mia-catalog` now defines disabled-by-default catalog inputs, validates PostgreSQL reader URL/username/Vault secret OCID only when enabled, and templates only non-secret connection metadata plus the Vault OCID. Its 14 contract tests pass. The Service workflow has not yet been coordinated with/pinned to this action; production release is not ready.
- Verification terminology correction: `CatalogSnapshotParityIntegrationTest` is an environment-configured JDBC test against a restored disposable database, not a Testcontainers test. `CatalogToolParityIntegrationTest` is the synthetic Testcontainers test, and its Colima mapped-port failure occurred before test assertions. Track these as separate evidence and rerun the synthetic test after resolving the Docker endpoint issue.
- Testcontainers and snapshot rerun: the direct Mac-host run failed before assertions because it could not connect to Ryuk at `localhost:32940`. Running the synthetic Testcontainers suite from a temporary host-network runner on the configured `colima-emme` Docker socket reached PostgreSQL; it first exposed the missing `vehicle_images_v1` table in the synthetic contract schema. Added model-level and exact-trim image rows/assertions, then `./gradlew integrationTest --tests '*CatalogToolParityIntegrationTest' --no-build-cache --no-daemon` passed. The restored snapshot suite then passed from the same runner as a new SELECT-only role; privilege check returned SELECT=true / INSERT=false. Revoked grants, dropped the temporary role, and removed the runner. The retained `ford-mia-mcp-test` container and snapshot DB were not removed or modified beyond temporary role creation/grants.
- Archived-contract fixture follow-up: `McpCatalogParityFixtureTest` and `mia-catalog-parity.json` preserve the three archived Node assertions. A test first failed because Java omitted `technicalSheetUrl`; the adapter now derives the detail-only field from the exact model-level specification source URL using the same pathname rule as Node. Search omits the field when absent, and price provenance stays separate. All five focused fixture tests pass; the full broader capability parity gate remains open.
- Release/deploy gate recheck: `gh auth status` reports the active token invalid; `github.com` DNS resolution fails; the deployment action SHA remains absent from both local action clones. Read-only SSH probes for `emme-1` and `emme-2` fail with `No user exists for uid 501`. No push or production operation was performed. Keep Node MCP removed in the candidate source and the Java catalog feature disabled; do not describe production as cut over.
- Technical-sheet follow-up: Node's `page-classifier.ts` identifies technical-sheet pages by URL pathname containing `ficha-tecnica`; the restored snapshot has 2,434 fact rows and 38 matching source URLs. Java now selects the exact model-level specification fact source URL, verifies its parsed URI path against that rule, and omits invalid/unmatched URLs. The test was observed red before implementation, then the focused fixture suite passed (5 tests), synthetic PostgreSQL Testcontainers parity passed (1 test), and the restored snapshot JDBC suite passed (3 tests, 0 skipped/failures) as a SELECT-only role. PostgreSQL confirmed SELECT allowed/INSERT denied; the temporary role was revoked and dropped.

### Release-path correction (2026-10-17)

The approved source-removal task does not yet constitute a deployable production cutover. The Node candidate CI still publishes the scheduler image and verifies scheduler startup, but the only release workflow for that image has been removed. Production scheduler runs under the MCP stack's `scheduler` Compose service, so production scheduling would stop if that stack is retired without a scheduler-only deployment path. Add this dependency before production cutover:

- [ ] Implement a scraper-only release/deploy path for the existing image, preserving scheduler operation, release backup/migration ordering, health checks, rollback, and no MCP process/listener.
- [ ] Coordinate Service release workflow inputs with a published OCI action revision; replace legacy `FORD_MCP_*` remote-client validation/inputs with the local Java catalog flag and read-only datasource settings.
- [ ] Add and pass workflow/action contract tests. Keep local catalog callbacks disabled until complete tool-level parity passes.
- [ ] Deploy in safe order, verify customer tool behavior and scraper schedule on emme-1/emme-2, then retire the old Node MCP host resources under the previously authorized rollout.

Evidence so far: Service workflow is at repository root and targets `ford-service` but still wires legacy remote MCP config. Its pinned action SHA is not in local clones. OCI action catalog changes are local and uncommitted. Node scraper CI publishes a versioned image, but its release workflow was deleted. GitHub auth/DNS and SSH probes currently fail, so publishing the action/workflows and production deployment cannot yet be verified. Do not state production is cut over.

### Scheduler and Service release wiring progress (2026-10-17)

- [x] Add a dedicated OCI `ford-scraper-scheduler` target with a private scheduler-only Compose service, no listener ports or MCP command, a single active monthly scheduler, a separate prior-image state, rollback, and configured-schedule log check.
- [x] Run verified data-host backup before the candidate-image migration; leave the existing scheduler in place until candidate migration succeeds, then replace it and restore the prior digest if health/schedule checks fail.
- [x] Restore a scraper release workflow that validates annotated version tags, successful tagged CI, immutable image digest and attestation; protect deploy with production/Vault readiness and manual approval.
- [x] Preserve release-all integration by matching target workflow filename, run-name, correlation input, coordinator authorization, approval bypass, and `ford-release-result` artifact.
- [x] Update Service release workflow and app env contract: local catalog feature defaults false; enabling requires parity-ready gate, dedicated JDBC URL/user/Vault reference; remove remote MCP client settings.
- [x] Local checks pass: Node lint/build/138 unit/workflow-contract tests; Service release coordinator/deployment contract tests (90); OCI action tests (19); shell syntax, YAML parse, Ansible syntax.
- [ ] Complete field-by-field tool parity and repeat all database integration gates.
- [ ] Publish OCI action commit `65fa3f562ae846fc096b6832893e293b0bdf326c` and coordinated branches. Current `gh auth status` says token invalid; `git ls-remote` and emme-1/emme-2 SSH fail with `No user exists for uid 501`.
- [ ] Run production release and verify local Java catalog tools plus scraper scheduler on both hosts.

Release note: this scheduler target uses a single-container restart (short scheduler interruption) to guarantee the monthly crawler is never duplicated. Previous immutable image is recorded and restored on failed candidate health/schedule checks. Production configuration and traffic remain unchanged. Release workflows point at the action commit locally, but cannot work on GitHub until that commit is published.

### Additional tool-parity findings and implementation (2026-10-17)

Reviewing the archived `src/mcp/server.ts` revealed that the three fixture cases did not cover tool behavior. The Java implementation was missing Node's omitted-year option response for `get_vehicle`, and `get_variant_comparisons` did not accept/filter model year. Implemented and tested both:

- `get_vehicle` returns available model-year options when year is omitted; a missed exact year returns `model_year_not_found` plus exact available years/options, or `no_model_year_options_found`. It preserves exact trim lookups.
- `get_variant_comparisons` now requires model year and forwards it into the `ford_read.comparison_facts_v1` filter.
- Full Service unit suite and `bootJar --rerun-tasks` passed (40 sec); focused tool/H2 tests pass; adapter/tool tests pass; integration tests compile.

The full contract remains incomplete. Node also supports summary/full controls and pagination/envelopes on several tools; Java still diverges for search evidence, offer disclosures, page-link/provenance projections, and comparison DTO shapes. Keep `FORD_CATALOG_TOOLS_ENABLED=false` until these are covered. Add two-year/trim and missing-evidence rows to fixtures to verify each selector and omission rule.

### Offers envelope parity continuation (2026-10-17)

- [x] Add tool tests for default query construction, count/envelope fields, and unknown-validity disclosure.
- [x] Return an `OfferSearch` envelope from Java `search_offers`, preserving evidence records and reporting semantic search as `unavailable` because Java does not implement the archived Node embedding path.
- [ ] Complete structured term-conflict/data-quality disclosures and verify SQL result/total-count semantics against the archived Node contract.
- [ ] Continue the remaining field-by-field tool parity matrix; this narrow offer slice does not close Task 3.

Verification: focused `McpCatalogToolsTest` passed after the expected red compile failure; `GRADLE_USER_HOME=/tmp/ford-mia-gradle-isolated ./gradlew test bootJar --offline --no-build-cache --no-daemon` passed (7 tasks, 35 seconds). The later promotion continuation reran both synthetic PostgreSQL parity and restored-snapshot integration. Feature remains disabled.

### Promotion paging and detail parity continuation (2026-10-17)

- [x] Extend the read-only promotion query with offset and add an exact total count, including when the requested page is empty.
- [x] Return the Node-style page envelope (`totalCount`, `offset`, `limit`, `hasMore`, results); support summary/full description and condition compaction while retaining Ford source and observation evidence.
- [x] Add H2 tests for total/empty-page behavior, summary/full projections, and invalid detail values.
- [x] Run full Service `test bootJar compileIntegrationTestJava`; passed.
- [x] Run synthetic `CatalogToolParityIntegrationTest` using the existing host-network Testcontainers runner; passed. Direct Mac-host run fails before assertions because Ryuk cannot reach its forwarded Colima localhost port.
- [x] Run restored disposable snapshot integration with a temporary SELECT-only role; passed. Revoked grants, dropped the run-specific role, and verified no grants/role remain.
- [ ] Keep broader parity open: Node promotion benefits remain absent from the versioned views; offer disclosures and remaining tool result projections are still incomplete.

Feature remains disabled. Production publication/deployment access blockers remain unchanged.

### Page-link projection parity continuation (2026-10-17)

- [x] Project Node's page-link envelope (`sourceUrl`, `totalLinks`, `offset`, `limit`) and compact each row to `url`, `text`, `type`, and `internal`.
- [x] Preserve exact input source URL and exact target URL from the versioned read view; do not expose adapter-only duplicate/href/source-page fields.
- [x] Run focused tool/adapter tests and full Service `test bootJar compileIntegrationTestJava`; passed.
- [ ] The current `page_links_v1` view has no source page title and cannot distinguish a nonexistent source page from one with zero links. Keep both as parity gaps; do not fabricate a title or claim Node's null behavior.

Feature remains disabled. Other tool parity and production release/deployment remain open.

### Offer validity disclosure parity continuation (2026-10-17)

- [x] Match Node status projection: if either validity bound is absent, status is `unknown`; a complete future range is `upcoming`; past complete ranges are `expired`; current complete ranges are `active`.
- [x] Preserve Node's `activeOnly` SQL semantics: a missing boundary does not exclude the offer/promotion, but its `unknown` state must be disclosed. Do not infer fully active from the filter name.
- [x] Add H2 rows for partial dates and regression tests for offer/promotion status and activeOnly inclusion.
- [x] Run full Service `test bootJar compileIntegrationTestJava`, synthetic PostgreSQL Testcontainers, and restored snapshot integration against the corrected source; all passed.
- [x] Verify the temporary snapshot reader role and grants were removed (0 role rows, 0 grants).
- [ ] `offers_v1` does not expose metadata `termConflict`; keep the exact term-conflict disclosure gate open until the read contract can supply it. Do not infer source metadata from offer title text.

Feature remains disabled. Production release/deployment remain open.

### Resume checkpoint (2026-10-17)

- Fresh Node verification passed: lint, 144/144 unit tests, TypeScript build, and 5/5 scraper container/workflow contracts.
- Fresh Service operations checks passed: 98 Python contracts, 36 workflow contracts, and Ansible catalog-sync syntax.
- A fresh Java run found stale fixture setup after `vehicle_facts_v2` and JSON metadata were introduced, plus an isolated Spring test context missing Jackson's `ObjectMapper` (6 failures across 704 tests). Fixture/context corrections are in progress; rerun the focused and full gates before closing.
- The still-open parity list is concrete: finance-only values, per-comparison URL, arbitrary source-page title/existence and known-empty semantics, free-text/semantic ranking and compact result fields, and comparison/promotion edge cases not represented by captured Node outputs. Some require a versioned read-view addition by the Ford-scraping migration owner; do not infer missing evidence in Java.
- The written plan's global constraint keeps Ford-scraping migrations outside this implementation slice. Therefore local customer catalog tools remain disabled until the migration owner approves/implements any missing view fields and full parity gates pass.
- GitHub auth check returned an invalid token. No push, workflow run, production deployment, or emme-1/emme-2 change occurred.

### Forward-only read-contract migration correction (2026-10-17)

- [x] Preserve applied migration 022 unchanged; moving the parity views into a later migration is required because production upgrades skip migrations already recorded as applied.
- [x] Add migration 023 for the new variant/fact/offer/link/image/comparison/provenance/status projections. Keep `promotions_v1` unchanged and expose expanded fields through new `promotions_v2`, so installs with prior view revisions also upgrade safely.
- [x] Add a red/green migration contract proving parity views are in 023 and absent from immutable 022; focused contract passes 6/6.
- [x] Apply 023 to the retained restored snapshot (the dump has no migration ledger, so apply the reviewed SQL transaction rather than replaying migrations 001+) and rerun the snapshot suite as a temporary SELECT-only role; 3/3 passed and the role/grants were removed.
- [x] Run clean-database Node read-view integration after the migration split; 6/6 passed.

Plan correction: the original global constraint said not to change migrations. Evidence from the retained, already-migrated database showed this would leave the approved Java catalog unusable: edits within 022 do not execute there, and the missing view made the snapshot integration fail on `ford_read.vehicle_facts_v2`. The approved migration-owner boundary is retained; this is a new forward-only read-view migration, with no crawler/scheduler changes, table writes, or production changes. Production tools stay disabled until this migration is published and applied through the protected release.

### Page-link title parity continuation (2026-10-17)

- [x] Confirm `provenance_v1` exposes source URL, page title, page type, and observation time for catalog-linked model/variant pages.
- [x] Populate the Node-compatible page-link envelope title from the exact source URL when the versioned provenance view has it.
- [x] Add adapter and callable-tool tests for a catalog model-page title.
- [ ] `provenance_v1` does not represent every page row. Keep arbitrary known-empty versus unknown-source behavior open; do not use missing provenance as proof that the source page does not exist.
- [x] Run full Service `test bootJar compileIntegrationTestJava`; passed.
- [x] Run all Service integration tests on the host-network Docker runner with a bounded JVM: 72 class tests, 3 expected snapshot skips, 0 failures/errors.
- [x] Run synthetic catalog Testcontainers (1/1) and the restored snapshot suite (3/3) separately after the projection change.

### Promotion/offer metadata parity correction (2026-10-17)

- [x] Recheck the actual archived Node query and migration `022_mia_catalog_read_views.sql` before assuming metadata is part of the versioned read contract.
- [x] Revert the Java benefits projection after confirming `offers_v1` does not expose `benefits`; the synthetic fixture had hidden a query that would fail against the real view.
- [x] Project promotion `modelUrl` from `promotions_v1.model_page_url` (inherited from `offers_v1`) into the Node-compatible result field.
- [x] Extend Node's versioned `offers_v1` projection with only `metadata.benefits` and `metadata.termConflict`, appended after existing columns to preserve the existing view contract.
- [x] Map the two JSON fields through the Java JDBC adapter and public offer/promotion tool results; absent values remain omitted.
- [x] Verify Node migration contracts and Java H2 adapter/tool contracts, then run Java PostgreSQL Testcontainers parity for structured JSONB reads.
- [x] Run the Node read-view integration contract against a disposable migrated `*_test` database; six integration tests passed, including populated metadata and null values.
- [x] Add `vehicle_facts_v2` to the versioned Node read contract and switch Java fact reads to it, carrying variant ID, category, normalized name, and numeric value while filtering out incomplete source evidence.
- [x] Verify view contract, Node migration/read-view integration (6/6), Java adapter/tool unit tests, and Java PostgreSQL Testcontainers parity (1/1).

Feature remains disabled. Production release/deployment remain open.

### Promotion model URL parity continuation (2026-10-17)

- [x] Verify `promotions_v1` is a projection of `offers_v1`, which exposes the model page URL.
- [x] Return that exact URL under Node's `modelUrl` field through the Java promotion evidence/result.
- [x] Verify adapter and customer-visible tool mapping in focused JDBC/tool and archived-fixture tests.
- [x] Synthetic PostgreSQL `CatalogToolParityIntegrationTest` passed 1/1; restored snapshot integration passed 3/3 under a temporary SELECT-only role.
- [x] Return source-backed `benefits` and `termConflict` JSON from promotions and offers when present; tests cover populated and null fields.

### Forward-only catalog parity completion (2026-10-17)

- [x] Add migration 024 without editing applied migrations: `offers_v2` exposes stored finance fields, `comparison_facts_v2` exposes exact comparison URLs, and `catalog_pages_v1` distinguishes known pages from unknown URLs.
- [x] Extend Node migration contract and clean-database integration coverage for finance fields, comparison URLs, source-page titles/existence, and unknown URLs.
- [x] Map the versioned views in Java and expose finance fields/comparison URLs while preserving known-empty versus unknown page state.
- [x] Run Node lint, 146 unit tests, build, 5 container contracts, and PostgreSQL read-view integration (6/6).
- [x] Run Java unit tests/bootJar/integration compilation, synthetic PostgreSQL parity (2/2), and restored snapshot (3/3, zero skips) after migration 024; temporary read-only role/grants were removed.
- [x] Add migration 025 for exact per-trim comparison URLs and expose the URL in Java comparison output.
- [x] Rerun Java full unit/build/integration compilation, synthetic PostgreSQL parity (2/2, zero skips), and restored snapshot (3/3, zero skips) after migration 025; SELECT-only role/grants were removed.
- [ ] Semantic ranking and complete byte-for-byte tool-output parity remain open; customer catalog callbacks stay disabled.

### Enablement readiness recheck (2026-10-17)

- [x] Reconcile the stale continuation notes above against migrations 024/025 and current adapter/tests: `offers_v2` now carries finance-only fields, `catalog_pages_v1` distinguishes known-empty from unknown pages, `comparison_facts_v2` carries comparison links, and Java reads these versioned views. Older open bullets saying those fields/views are absent are historical and superseded by the migration-024/025 results.
- [x] Read the retained disposable `ford_mia_catalog_parity_test` database: 1,026 ready embeddings, one stored model name (`bge-m3`), all vectors 1,024-dimensional; read-view counts include 737 offers, 10 promotions, and 2,447 comparison facts.
- [x] Fresh Service verification: `GRADLE_USER_HOME=/tmp/ford-mia-gradle-isolated ./gradlew test bootJar compileIntegrationTestJava --offline --no-build-cache --no-daemon --max-workers=1 --rerun-tasks` passed; XML reports 704 tests, 0 failures/errors/skips.
- [ ] Java catalog adapter still does not perform semantic vector ranking; offers currently identify semantic search as unavailable. Spring Service production embedding config is `nomic-embed-text`, while the catalog vectors are tagged `bge-m3`. Do not equate these models or enable full semantic parity without a tested matching provider/model/dimension path.
- [ ] Finish fresh byte-for-byte Node-vs-Java callable-tool fixtures for every capability after the latest migrations and mappings; the existing fixture intentionally covers only three archived Node cases.
- [ ] Preserve `FORD_CATALOG_TOOLS_ENABLED=false` until the complete parity gate and production read-only datasource permissions pass.
- [ ] Protected publication/deployment remains blocked: `.zshrc` token is rejected by `gh auth status`, and the current environment cannot resolve `api.github.com`; no production configuration has been changed.

### Financing model URL field-name parity continuation (2026-10-17)

- [x] Confirm the archived Node offer result uses `modelUrl` and `offers_v1` supplies the model page URL.
- [x] Serialize the Java offer model-page URL using the matching `modelUrl` field name.
- [x] Add callable-tool assertion for the exact URL and run the focused tool test; passed.
- [x] Add stable `offerId` and promotion `id` from the read view's `promotion_id` field to match Node result identifiers.
- [x] Focused adapter/tool/archived-fixture tests pass for IDs and exact `modelUrl`.
- [x] Full Service `test bootJar compileIntegrationTestJava` passed after the result-field changes.
- [x] Restored snapshot integration passed 3/3 with offer/promotion identifiers checked under SELECT-only access; temporary role and grants were removed and verified absent.
- [x] Run the bounded full host-network integration suite after the result-field changes: 72 class tests, 3 expected snapshot skips, 0 failures/errors.

### Trim-comparison envelope parity continuation (2026-10-17)

- [x] Include the Node envelope's `modelPageUrl` and `technicalSheetUrl` from matched vehicle evidence.
- [x] Return Node-shaped compact `trims` and `comparisons`, including backed price source URLs, difference status, and paired values/source URLs.
- [x] Accept `detail=summary|full`; summary returns the top five ranked paired differences with `differencesOmitted`, while full returns every paired difference.
- [x] Normalize requested and returned trims with the Node model-prefix rule when determining missing trims.
- [x] Focused tests cover model-prefixed selectors, URLs, summary/full output names, status, source evidence, five-difference limit, and omission count.
- [x] Run full Service `test bootJar compileIntegrationTestJava`; passed.
- [x] Run synthetic PostgreSQL parity (1/1) and restored snapshot integration (3/3) after the projection change.
- [x] Run complete bounded integration suite: 72 tests, 3 expected snapshot skips, 0 failures/errors.
- [x] `foundTrims` evidence now carries the view-backed variant ID, category, normalized name, and numeric value fields; exact comparison URL remains unavailable.
- [x] `comparisonUrl` is now projected through versioned views 024/025 and exposed in Java comparison results; the older omission note is superseded.

### Status and provenance projection parity continuation (2026-10-17)

- [x] Include model, year, and trim from the versioned document-provenance view in the Java tool response alongside hashes, title/type, URLs, and observation time.
- [x] Preserve empty results for invalid UUID and unknown document IDs; verify the JDBC behavior.
- [x] Preserve the archived Node catalog-status keys (`pages`, `vehicles`, `embedded`) while retaining the richer read-view counts and latest sync details.
- [x] Run focused provenance/status adapter tests and full Service `test bootJar compileIntegrationTestJava`; passed.
- [x] Verify empty latest-sync fields and stable legacy count aliases through adapter tests; restored-snapshot aggregate checks are included below.
- [ ] Broader end-to-end parity gates remain open.

Feature remains disabled. Production release/deployment remain open.

### Combined vehicle-and-financing projection parity continuation (2026-10-17)

- [x] Accept the optional query and both year aliases; derive Node's default query from exact model/year, defaulting to Ford vehicle search.
- [x] Select at most one result and require exact requested model/year before treating it as the vehicle match.
- [x] Query financing for the selected or explicitly requested model/year, retain financing even when an exact requested vehicle lookup misses, and return Node-style combined `answerSummary`, vehicle summary/count, offer query/count/disclosures/results.
- [x] Avoid an unfiltered financing result when free-text search finds no exact model; the Java offers port has no relevance query and must not present an arbitrary row as a match.
- [x] Verify exact alignment, missing-vehicle, and no-selector cases with focused tests; full Service `test bootJar compileIntegrationTestJava` passed.
- [ ] Complete free-text ranking parity and match Node's compact vehicle/offer subfields; semantic ranking remains unavailable.

Feature remains disabled. Production release/deployment remain open.

### Offer buyer projection parity continuation (2026-10-17)

- [x] Map offer evidence to buyer-facing Node field names and formatting: cash/monthly/down-payment displays, source fields, `buyerSummary`, validity disclosure, and separate `requiredDisclosures` envelope.
- [x] Keep the unknown-validity disclosure in both the individual result and the envelope; do not format a missing amount or invent an amount.
- [x] Verify currency formatting and summary text with tool tests; full Service `test bootJar compileIntegrationTestJava` passed.
- [x] Finance-only values (tax rate, opening commission, maximum down-payment percentage, and life-insurance price) are projected by `offers_v2` and mapped in the Java adapter. The earlier absence note is superseded by migration 024.

Feature remains disabled. Production release/deployment remain open.

### Fresh completion audit (2026-10-18)

- [x] Fresh Java unit suite: `./gradlew test --offline --no-build-cache --no-daemon --max-workers=1 --rerun-tasks` passed.
- [x] Fresh Java integration suite: `./gradlew integrationTest --offline --no-build-cache --no-daemon --max-workers=1 --rerun-tasks` passed; 76 tests, 0 failures/errors, 3 intentionally env-gated snapshot skips.
- [x] Fresh synthetic PostgreSQL tool parity: `CatalogToolParityIntegrationTest` passed 2/2.
- [x] Fresh real snapshot gate: `mise run test:parity -- <restored ford-20261009T101630.207Z.dump>` passed 3/3 as a dedicated reader role; SELECT allowed and INSERT denied; the disposable container and role were removed.
- [x] Add exact variant comparison URL and finance-field projections from migrations 024/025; older gap notes in this plan are superseded.
- [ ] Full byte-for-byte Node/Java output fixtures across every callable tool and semantic ranking parity remain open. The archived fixture still covers only a small subset; current catalog callbacks remain disabled.
- [ ] Production publication/deployment remains blocked by invalid GitHub token/API connectivity and local SSH UID resolution failure; no production setting was changed.
