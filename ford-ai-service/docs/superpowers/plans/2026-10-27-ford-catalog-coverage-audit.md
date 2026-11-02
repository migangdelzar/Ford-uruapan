# Ford Catalog Coverage Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Report exactly what the current read-only Ford catalog snapshot covers and what it lacks, without claiming coverage of unpublished data or writing to the protected catalog database.

**Architecture:** Audit the approved snapshot through the existing `CatalogPort` and snapshot manifest. Keep the application catalog datasource read-only. Report model/year/trim, fact, price, offer/promotion, provenance, and freshness coverage as a build artifact. Population beyond the approved snapshot belongs to the upstream Ford-scraping source owner; this repo must not write directly to `ford_read` or repurpose the `ford_service` writer.

**Tech Stack:** Java, Spring JDBC, JUnit 5, AssertJ, Gradle integration tests, disposable PostgreSQL snapshot import.

## Global Constraints

- `CatalogDataSourceConfiguration` requires a dedicated read-only catalog datasource and rejects the `ford_service` database.
- The app's `JdbcCanonicalDataWriter` writes `ford_service` tables and does not populate the `ford_read` views.
- Do not create a second customer-query catalog database or grant the app write access to the read-only datasource.
- Only source-backed facts with source URL and observation time support factual answers.
- Missing or conflicting evidence remains `UNKNOWN` or `CONFLICTED`.
- Vehicle catalog data never implies stock availability.
- Completeness means coverage of an explicit official public-source manifest, not all possible Ford knowledge.
- Keep customer text, personal data, secrets, and raw tool arguments out of audit artifacts.

---

## File Map

- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogSnapshotParityIntegrationTest.java` — audit real read-view coverage by identity and domain.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/LocalCatalogSnapshotLoader.java` only if the report needs additional manifest metadata already present in the approved snapshot format.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/LocalCatalogSnapshotLoaderTest.java` — prove missing/unconfigured snapshot data is reported, not counted as complete.
- Create `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogCoverageAudit.java` — produce a bounded structured coverage report from read-only `CatalogPort` queries.
- Create `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogCoverageAuditTest.java` — verify counts, gaps, dates, and source provenance with synthetic fixtures.
- Create or extend `ford-ai-service/src/integrationTest/resources/catalog/ford-mx-coverage-requirements.json` — declare audited data domains and source expectations; do not invent Ford variants or facts.

### Task 1: Define the coverage report contract

**Interfaces:** `CatalogCoverageAudit.Report` records snapshot label/hash/time, recorded source states, whether the official source scope is verified, unique model/year/trim counts, and per-domain counts for sourced facts, prices, comparisons, offers, and promotions. It also has one coverage row per canonical model/year/trim, with package identity explicitly `UNKNOWN` when the read schema cannot express it. It includes explicit unknown/gap counts and an offers-truncated flag when the 50-row API limit is reached. It contains no buyer messages, credentials, or raw payloads. Missing source-state metadata means `NOT_RECORDED`, never `SUCCESS`.

- [ ] **Step 1: Add synthetic audit tests**

Create `CatalogCoverageAuditTest` with a fake `CatalogPort`. Include one sourced variant with facts and price, one variant with no facts, one expired promotion, one active promotion, and a missing source state. Assert counts by domain and that missing facts are reported as gaps—not as absent features.

- [ ] **Step 2: Run the new test and confirm failure**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.catalog.CatalogCoverageAuditTest
```

Expected: FAIL because the report type and audit implementation do not yet exist.

- [ ] **Step 3: Implement the immutable report and auditor**

Add `CatalogCoverageAudit` under the integration-test source set. Use `CatalogPort.nameIndexEntries()` for canonical identities, `find(VehicleLookup)` for each identity's source-backed facts and price, paginated `promotions(PromotionQuery)` for dated promotions, `offers(OfferQuery)` for finance products, and `status()` for catalog-level counts. Since `OfferQuery` has a 50-row cap and no offset, set `offersTruncated` when 50 rows return. Group observed source hosts from safe public source URLs. Mark official-source scope `UNVERIFIED` until the upstream source manifest explicitly lists its checked official sources and statuses. Do not update the catalog datasource.

- [ ] **Step 4: Run the audit unit-style integration test**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.catalog.CatalogCoverageAuditTest
```

Expected: PASS; report is immutable, deterministic, and separates unknown from confirmed absence.

### Task 2: Compare the approved snapshot with the report

- [ ] **Step 1: Add a real-snapshot parity test**

Extend `CatalogSnapshotParityIntegrationTest` to run `CatalogCoverageAudit` against the configured disposable snapshot. Assert the current validated export rows are represented (39 vehicle-search rows, 121 variants, 4,887 vehicle facts, 2,447 comparison facts, 10 promotions, and 1,882 product vectors for the Oct 25 candidate). Assert factual rows retain source URLs and observation times. Treat these as snapshot parity counts, not proof that Ford publishes no other data.

- [ ] **Step 2: Run parity integration**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.catalog.CatalogSnapshotParityIntegrationTest
```

Expected: PASS only with the approved Oct 25 candidate snapshot configured; otherwise the test assumption skips cleanly.

- [ ] **Step 3: Emit a safe coverage artifact**

Write the report as JSON under `ford-ai-service/build/reports/catalog-coverage/`. Include snapshot hash, refresh time, row counts, domain gaps, and stale/expired counts. Do not include URLs with query secrets, raw source payloads, or customer information. Verify the output path is under `build/` and is not checked in.

### Task 3: Make source completeness explicit

- [ ] **Step 1: Add source-state tests**

In `LocalCatalogSnapshotLoaderTest`, cover absent snapshot path, missing source tables, empty files, manifest hash mismatch, and valid-but-partial snapshots. Assert each state is classified separately as `UNCONFIGURED`, `INVALID`, `EMPTY`, or `PARTIAL`; none may report `COMPLETE`.

- [ ] **Step 2: Run loader tests**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalCatalogSnapshotLoaderTest
```

Expected: PASS; empty or partial input never passes the completeness gate.

- [ ] **Step 3: Add explicit domain requirements**

Define required audit domains in `ford-mx-coverage-requirements.json`: canonical model/year/trim identity, package identity where the source publishes it, source-backed specifications, published price, dated finance products, and dated promotions. Mark physical inventory as a separate source/domain. A domain may be `NOT_PUBLISHED` only when an official source check supports that status; otherwise mark it `UNKNOWN`. The first report must say `UNVERIFIED` for full Ford Mexico model/trim/package coverage until the upstream source owner supplies a checked official-source manifest.

### Task 4: Document the population boundary and next gate

- [ ] **Step 1: Document the upstream source owner**

Update the catalog operations documentation in `ford-ai-service/docs/operations.md` with the verified boundary: the app reads `ford_read` through a dedicated read-only datasource, while `DefaultSyncCoordinator`/`JdbcCanonicalDataWriter` write the separate `ford_service` database. State that catalog population must use the approved Ford-scraping snapshot/sync owner and its backup/approval gate.

- [ ] **Step 2: Verify documentation and report behavior**

Run:

```bash
./gradlew test --tests com.ford.service.catalog.CatalogDataSourceConfigurationTest
./gradlew integrationTest --tests com.ford.service.catalog.CatalogCoverageAuditTest --tests com.ford.service.catalog.CatalogSnapshotParityIntegrationTest
```

Expected: PASS or explicit snapshot assumption skip. No command writes to a deployed database.

- [ ] **Step 3: Stop before external population without the source-owner gate**

Do not dispatch `catalog-sync`, alter emme-2, or replace a protected snapshot as part of this plan. The audit artifact must identify gaps first. Any upstream source changes require the Ford-scraping owner, official-source allowlist, backup verification, and approved deployment workflow.

### Task 5: Import the validated dataset into the isolated local catalog

**Target:** The user approved the isolated local `ford_catalog` database. Never target emme-2 or a deployed database.

- [ ] **Step 1: Confirm the approved source export and report**

Require a valid snapshot manifest, matching file hashes, completed coverage report, and explicit review of unknown fields. Do not import if source coverage is `INVALID`, `EMPTY`, or hashes mismatch. `UNVERIFIED` full-market scope must remain visible in the report.

- [ ] **Step 2: Start the isolated local catalog**

Run from `ford-ai-service`:

```bash
mise run catalog:start
```

Expected: only the local `catalog-postgres` service starts; no remote deployment or catalog writes occur.

- [ ] **Step 3: Write the approved snapshot to local `ford_catalog`**

Run:

```bash
FORD_LOCAL_CATALOG_EXPORT_DIR=/absolute/path/to/validated/source-export-oct25-candidate mise run catalog:import
```

Expected: importer validates the export and writes only to the isolated local `ford_catalog` database. Stop if importer target validation fails; never bypass safeguards.

- [ ] **Step 4: Verify local read parity**

Run the configured disposable snapshot parity and coverage tests:

```bash
./gradlew integrationTest --tests com.ford.service.catalog.CatalogSnapshotParityIntegrationTest --tests com.ford.service.catalog.CatalogCoverageAuditTest
```

Expected: imported row counts and hashes match the approved manifest; facts retain source URLs and observation times. Report any missing source data as unknown.
