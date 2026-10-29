# Catalog-Wide LLM Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve answers across every currently supported Ford catalog domain by extending the existing local catalog ETL, scoped profile generation, read views, Service tools, and Spanish evaluation.

**Architecture:** Build on—not replace—the local Ford-scraping candidate that already has scoped profile builders, brochure references, and local embeddings. Keep relational facts authoritative for exact identity, numeric specs, prices, finance, and dates; use scoped semantic documents to find descriptive evidence. Add a versioned canonical read view only if the data audit proves it is needed. Ford-service remains read-only. All schema/backfill work is confined to the verified local candidate database.

**Tech Stack:** TypeScript/Node 22, PostgreSQL/pgvector, SQL read-view migrations, Java 25, Spring JDBC/Spring AI tools, Vitest, JUnit 5, Testcontainers, local BGE-M3 and Qwen.

## Global Constraints

- Preserve all current uncommitted work in `/Users/miguelangeldelgadillozarate/.local/share/opencode/worktree/606289/eme2-captured-catalog-merge`; inspect before editing; never reset, clean, or overwrite it.
- Work only with local `ford_catalog_candidate_20261025` for schema/backfill changes. Verify host/container, database name, and recoverable backup before each write.
- Never alter baseline `ford_catalog`, emme-2, a deployed database, or production schema. No production writes or deployment.
- Do not run the live crawl/sync to populate the candidate; use the approved snapshot, reviewed capture artifacts, and local PDFs already staged.
- Preserve raw facts, prices, identities, source URLs, observation timestamps, and existing 299 baseline documents. No whole-database restore or deletion.
- Do not infer trim applicability, feature absence, dealer stock, subjective “easy to park,” offer eligibility, or a value from ambiguous source rows.
- Keep structured exact facts authoritative; embeddings only retrieve descriptive evidence. Preserve citation scope and model/year/trim identity.
- Run unit tests, then disposable PostgreSQL tests, then local candidate checks. No full 100-turn Qwen acceptance; historical unsupported-price discrepancy remains a separate gate.
- Keep repeated-greeting correction independent; see `docs/superpowers/plans/2026-10-29-no-repeated-assistant-greetings.md`.

---

## Existing Work to Preserve and Extend

Ford-scraping candidate worktree: `/Users/miguelangeldelgadillozarate/.local/share/opencode/worktree/606289/eme2-captured-catalog-merge`.

Existing design/status artifacts in that worktree:
- `docs/superpowers/specs/2026-10-25-catalog-ingestion-retrieval-improvements-design.md`
- `docs/catalog-candidate-retrieval-status.md`
- `docs/captured-catalog-import.md`

Existing implementation to review/reuse:
- `src/domain/catalog-profiles.ts` — source/category/trim-scoped profile text, safe exclusions, deterministic keys.
- `src/domain/brochure-profiles.ts` — conservative PDF page references and verified citations.
- `src/embeddings/local-catalog.ts` — local BGE-M3 embedding validation and token budget.
- `scripts/build-catalog-profiles.ts`, `src/crawl/captured-snapshot.ts`, and `src/storage/captured-price-repository.ts` — snapshot/profile/price safety boundaries.
- Service side in this worktree: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcCatalogRetrievalAdapter.java` and `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`.

Recorded local candidate baseline: 39 model-years, 121 variants, 4,887 facts, 2,447 comparison facts, 10 promotions, 1,882 ready vectors, and 113 variants with new trim-scoped profiles. Treat these as recorded candidate facts until read-only DB verification confirms current state. The existing status report says broad Spanish Spring AI/LangGraph evaluation is still missing.

### Task 1: Review existing user work and verify local candidate identity

**Files/locations:**
- Review only: candidate worktree files listed above.
- Review only: `/Users/miguelangeldelgadillozarate/Development/Ford-scraping` git status and migration history.
- Review only: `/Users/miguelangeldelgadillozarate/Development/Ford-uruapan/.worktrees/customer-choice-inventory-approval/ford-ai-service` current tool/retrieval code.

- [ ] **Step 1: Capture worktree state before changes**

Record `git status --short`, branch/HEAD, tracked diff names, and untracked file names for the candidate. Do not stage, reset, clean, move, or edit anything in this step.

- [ ] **Step 2: Verify the local candidate target read-only**

Use the documented local catalog tooling with read-only credentials to verify the selected container/context, `current_database() = ford_catalog_candidate_20261025`, existing `ford_read` views, candidate manifest/hash, and row counts. Do not print credentials or query customer/conversation data. If the target is not exact, stop.

- [ ] **Step 3: Review existing candidate design and implementation**

Compare the code against the recorded status report. Confirm profile builder exclusions, PDF page/quarantine rules, BGE model/dimension/token guards, candidate-only manifests, and whether previous pending work changed any of these contracts. Preserve all unrelated user changes.

- [ ] **Step 4: Write a safe baseline report**

Add a new candidate coverage report under `docs/` or the existing local candidate artifact directory. Report counts by model/year/trim and domain, existing/new profile coverage, source/provenance completeness, unmapped names, conflicts, and unknowns. Do not include raw source text or secret URL parameters.

**Gate:** Do not proceed to ETL or database changes until worktree ownership and database identity are confirmed and the user’s existing edits are understood.

### Task 2: Build the catalog-wide question and attribute coverage matrix

**Files:**
- Create: `evals/catalog-qa/catalog-domain-coverage.json` in Ford-scraping candidate.
- Modify: `evals/catalog-qa/catalog-inventory.json` only when backed by the approved snapshot/source manifest.
- Test: `tests/unit/catalog-qa-evaluation.test.ts` and relevant profile-builder tests.

**Interfaces:**
- Domains: canonical identity/trim, powertrain, performance, dimensions/capacity, safety/driver aids, comfort/technology, efficiency, pricing, finance, promotions, model/trim/year comparison, images/official links/provenance, and explicit unknown/unavailable behavior.
- Each case includes exact model/year/trim identity, question in Spanish, required evidence keys, expected evidence status, citation expectations, and allowed abstention/clarification.
- Inventory and dealer stock are explicitly unsupported by this catalog and must be declined or routed to a separate source.

- [ ] **Step 1: Add representative coverage tests before changing builders**

Cover exact trim identity, model-scoped facts, power/torque, dimensions, driver aids, consumption, current price, finance terms, active/expired promotion, trim differences, source citation, conflict, missing fact, and no-inventory claim. Use only facts actually present in the approved local snapshot or reviewed fixtures.

- [ ] **Step 2: Run focused unit tests and observe expected gaps**

```bash
npm run test:unit -- tests/unit/catalog-qa-evaluation.test.ts tests/unit/catalog-profiles.test.ts
```

Expected: current code passes existing contracts; newly added matrix assertions identify uncovered domains/variants without inventing expected source values.

- [ ] **Step 3: Record question-to-evidence contract**

For each category, distinguish whether answer comes from structured SQL, scoped profile retrieval, official PDF reference, comparison records, or must abstain. Prices/finance/promotions remain structured and date-checked. PDFs remain reference-only unless an exact reviewed fact binding exists.

- [ ] **Step 4: Run focused unit tests**

```bash
npm run test:unit -- tests/unit/catalog-qa-evaluation.test.ts tests/unit/catalog-profiles.test.ts
```

Expected: `PASS`; matrix validates identity, evidence status, citations, and abstention separately.

### Task 3: Improve canonical fact normalization and profile coverage

**Files:**
- Review/modify: `src/domain/normalization.ts`
- Review/modify: `src/domain/catalog-profiles.ts`
- Create only if mapping responsibility is too large: `src/domain/catalog-fact-registry.ts`
- Test: `tests/unit/catalog-profiles.test.ts`, `tests/unit/reviewed-powertrain-fixture.test.ts`, and a focused new normalizer test if a registry is created.

**Interfaces:**
- A canonical fact preserves `modelYearId`, nullable `variantId`, model/year/trim scope, source/category, original label/value/unit, source URL, observation timestamp, and a canonical attribute ID only when allowlisted.
- Supported normalized types: text, boolean, decimal with unit, and bounded enumerated value. Unsupported parse stays raw and unmapped.
- Conflicts, joined alternatives, generic listing markers, blank/dash values, and uncertain trim bindings remain excluded/quarantined with fixed reason codes.
- Price stays relational. No inferred `easy_to_park`, inventory, or cross-trim fact inheritance.

- [ ] **Step 1: Write failing normalization tests from audit evidence**

Test observed aliases for dimensions, engine/powertrain, safety/equipment, efficiency, and capacity; unit normalization only for reviewed units; duplicate category copies; conflicting values; invalid/missing source provenance; and shared model versus exact trim scope. Include a regression that `|`-joined alternative values are not assigned to a trim.

- [ ] **Step 2: Run focused tests and observe RED**

```bash
npm run test:unit -- tests/unit/catalog-profiles.test.ts tests/unit/reviewed-powertrain-fixture.test.ts
```

Expected: new alias/scope/normalization assertions fail before implementation.

- [ ] **Step 3: Implement a versioned, explicit attribute registry**

Map only reviewed source names. Keep canonical IDs stable and attach canonical unit/type. Use deterministic accent/case/punctuation normalization only for name matching; do not use semantic similarity to decide attribute identity. Preserve raw fact fields in every generated profile and record unmapped/excluded reason codes.

- [ ] **Step 4: Extend profiles by topic and exact variant**

Reuse `buildCatalogProfiles`. Build coherent topic groups (powertrain, safety, comfort/connectivity, dimensions/capacity, efficiency, and verified equipment). Include exact trim identity when `variant_id` is present; keep model-level facts separate. Avoid duplicate profiles for equivalent fact rows. Keep document-size/token limits and stable content hashes.

- [ ] **Step 5: Run profile/unit test gates**

```bash
npm run test:unit -- tests/unit/catalog-profiles.test.ts tests/unit/reviewed-powertrain-fixture.test.ts
npm run build
npm run lint
```

Expected: tests/build/lint pass; no raw source or database changes yet.

### Task 4: Decide and add the smallest useful local read-schema change

**Files:**
- Create forward-only migration under `db/migrations/` in the candidate Ford-scraping worktree, with next unused migration number after confirming current history.
- Modify: the matching `ford_read` view migration/query and parity tests only if required by the new canonical attributes.
- Create: `tests/integration/catalog-read-views.test.ts` — disposable PostgreSQL contract for the new read view.

**Decision rule:** Do not add columns because they look helpful. Add a versioned `ford_read` view only if Task 1–3 prove that the current `vehicle_facts_v2` contract cannot return canonical attribute IDs/types/units/status needed by exact tools or comparison. Keep raw source tables and already-applied migrations immutable.

- [ ] **Step 1: Write failing schema-contract integration tests**

Against disposable PostgreSQL, assert the proposed read contract preserves exact model/year/trim and provenance, maps only registered canonical facts, reports unmapped/unknown/conflict safely, and does not expose capture-only review metadata to the Service reader.

- [ ] **Step 2: Run integration tests and observe RED**

```bash
npm run test:integration -- tests/integration/catalog-read-views.test.ts
```

Expected: the test fails because the new versioned read contract does not exist.

- [ ] **Step 3: Add one forward-only read migration**

Create an additive view over current source/candidate rows; do not alter base facts, crawl behavior, or applied migrations. Keep database reader privileges `SELECT` only. Include migration ID and registry version in the contract; make rerun behavior safe through `CREATE OR REPLACE VIEW` or the repository’s migration discipline.

- [ ] **Step 4: Run clean and restored-snapshot parity tests**

```bash
npm run test:integration -- tests/integration/catalog-read-views.test.ts
```

Expected: disposable schema and restored approved snapshot produce the same view contract; no command targets baseline or production.

### Task 5: Extend the local candidate transactionally

**Files:**
- Review/modify only after Task 1 worktree inventory: `scripts/build-catalog-profiles.ts`, `src/storage/catalog-repository.ts`, and existing candidate writer/manifest code.
- Create if absent: `tests/integration/catalog-profile-repository.test.ts` — candidate profile transaction, idempotency, and preservation checks.

- [ ] **Step 1: Prove apply plan on disposable PostgreSQL first**

Build a deterministic manifest from the verified approved snapshot. Include source snapshot hash, builder/registry version, exact identities, inserts/updates/unchanged/quarantined counts, and profile content hashes. Assert running the same plan twice adds no duplicate facts/documents and leaves original records unchanged.

- [ ] **Step 2: Run isolated writer tests**

```bash
npm run test:integration -- tests/integration/catalog-profile-repository.test.ts
```

Expected: transaction rollback on injected failure; no writes outside the explicitly named disposable test database.

- [ ] **Step 3: Back up and verify the local candidate target**

Use the existing local backup workflow. Verify the command resolves to `ford_catalog_candidate_20261025` on the selected loopback `colima-testbox` catalog container. Never read or print passwords. Stop on any name/host mismatch or if the backup is incomplete.

- [ ] **Step 4: Apply only approved canonical/profile changes to the candidate**

Apply the hash-bound plan transactionally to `ford_catalog_candidate_20261025`; preserve baseline tables/documents and existing candidate manifests. Do not re-import the full snapshot. Read back every touched identity, source, scope, fact value, profile key, and vector dimension before commit. Record before/after counts and manifest hashes.

- [ ] **Step 5: Verify candidate preservation**

Compare baseline and candidate fingerprints for untouched source rows, prices, offers, comparisons, and original 299 documents. Report new profile coverage by all 121 variants; any remaining unknown/unmapped data stays visible.

### Task 6: Improve Ford-service retrieval and tool evidence

**Files:**
- Modify in the current Ford-uruapan service worktree: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcCatalogRetrievalAdapter.java`.
- Modify only if contract requires: `ford-ai-service/src/main/java/com/ford/service/catalog/api/VehicleFact.java`, `VehicleEvidence.java`, `DocumentProvenance.java`, `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`.
- Test: existing `CatalogSemanticRetrievalTest`, `CatalogSemanticRetrievalIntegrationTest`, `CatalogToolParityIntegrationTest`, `CatalogToolsTest`, and `CatalogSnapshotParityIntegrationTest`.

**Interfaces:**
- Preserve existing tool names. Do not add a duplicate generic “ask catalog” tool.
- Exact questions use exact SQL facts/prices and exact model/year/trim resolution. Descriptive questions may use scoped profile/PDF retrieval with citations.
- Keep separate retrieval lanes for Ford profiles, exact-trim facts, model-level references, competitor comparisons, brochures, and promotions.
- Every returned claim carries identity scope and provenance; absence never proves a negative feature.

- [ ] **Step 1: Add failing cross-source and exact-scope tests**

Test exact trim profile retrieval; no profile leakage across trims/years; no competitor document in Ford profiles; winning source page/provenance retained; canonical numeric values still come from structured facts; promotion expiry remains structured; local candidate view works under read-only role.

- [ ] **Step 2: Run focused service unit and integration gates**

```bash
./gradlew test --tests com.ford.service.catalog.CatalogSemanticRetrievalTest --tests com.ford.service.ai.CatalogToolsTest
./gradlew integrationTest --tests com.ford.service.catalog.CatalogSemanticRetrievalIntegrationTest --tests com.ford.service.catalog.CatalogToolParityIntegrationTest --tests com.ford.service.catalog.CatalogSnapshotParityIntegrationTest
```

Expected initially: new parity assertions fail on any missing view/identity/evidence behavior; existing tests remain diagnosable.

- [ ] **Step 3: Implement only the failed retrieval contract**

Bind a document to a trim only through matching `variant_id` and `model_year_id`; keep model-only pages model-scoped. Exclude competitor documents from Ford lanes. Carry winning document/source page to final evidence. Keep exact structured values and authorization paths unchanged.

- [ ] **Step 4: Run focused service gates**

Repeat the focused commands in Step 2. Expected: `BUILD SUCCESSFUL`; reader role has SELECT only; no app writes or external delivery.

### Task 7: Evaluate all supported catalog question classes locally

**Files:**
- Extend: Ford-scraping `evals/catalog-qa/questions.jsonl` or current catalog QA fixture.
- Extend: Ford-service deterministic catalog evaluation fixtures and report writers.
- Update: `docs/catalog-candidate-retrieval-status.md` in the candidate worktree and the catalog design spec in this repo.

- [ ] **Step 1: Add a reviewed Spanish baseline/candidate test set**

Include model/year/trim resolution, powertrain, performance, dimensions/parking, safety, comfort/technology, capacity, efficiency, price, financing, promotion validity, model/trim/year comparisons, official-source references, unknown/conflicting facts, and inventory-abstention cases. Add multi-turn continuations that ensure acknowledged buyer needs are not ignored. Every expected answer cites a specific source or expects abstention/clarification.

- [ ] **Step 2: Run deterministic evaluation before local model calls**

Run all Ford-scraping unit/build checks, all Ford-service unit tests, then focused disposable PostgreSQL parity. Compare baseline and candidate separately for exact-trim accuracy, evidence recall/relevance, citation correctness, unsupported claims, unknown handling, and latency. No single aggregate score may hide wrong trim/year.

- [ ] **Step 3: Run a bounded local Qwen diagnostic only if deterministic gates pass**

Use the local candidate database, local Qwen/BGE, cloud disabled, no delivery beans, fresh replay state, and a reviewed short question set. Do not run the 100-turn acceptance suite. Stop on the first safety/evidence/identity violation and preserve artifacts.

- [ ] **Step 4: Record limitations honestly**

Report profile coverage per variant/domain, quarantined PDF pages, unmapped source names, stale/conflicted facts, unsupported inventory, price discrepancy status, answer metrics, and remaining work. Do not claim all Ford knowledge or production readiness.

## Separate Greeting Plan

Repeated greeting behavior stays separate from catalog ETL and retrieval. Execute `docs/superpowers/plans/2026-10-29-no-repeated-assistant-greetings.md` independently after catalog worktree/user-change review.

## Database Boundary

User approved read and schema/data changes on the local candidate. Permitted target is only `ford_catalog_candidate_20261025` on the verified loopback catalog container, with backup and candidate-bound manifest. The baseline `ford_catalog`, `ford_service`, emme-2, and all deployed/production databases remain off-limits. Ford-service credentials remain read-only. Never apply the approved filtered snapshot over the full catalog.

## Self-Review

- Existing candidate code and uncommitted user work are the starting point, not cleanup targets.
- Structured facts and scoped semantic documents have separate authority; vectors do not establish exact values.
- Registry/view work is justified by audit and is forward-only; no unnecessary schema columns.
- All supported catalog domains have explicit evidence/abstention evaluation cases.
- Local candidate writes require verified target, backup, hash-bound transaction, and read-back.
- No production write, deployment, external delivery, or 100-turn Qwen acceptance is included.
