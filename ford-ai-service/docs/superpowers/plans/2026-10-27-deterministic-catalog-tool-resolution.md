# Deterministic Catalog Tool Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve a unique trim-family mention to its sole canonical trim, preserve canonical clarification for multiple matches, and prevent turn 74 from ending in a generic evidence-review fallback.

**Architecture:** Keep canonical identity matching in `CatalogVehicleResolver`, backed by the existing names-only `CatalogPort.nameIndexEntries()` read. Extend the typed resolution result to distinguish a unique canonical-prefix match from an exact name and a multi-choice ambiguity. `CatalogTools` performs the fact lookup only for an exact or uniquely resolved canonical identity; multi-choice results remain clarification-only and do not count as vehicle facts.

**Tech Stack:** Java, Spring Boot, Spring AI tool callbacks, JUnit 5, AssertJ, Gradle unit and integration tests, PostgreSQL catalog snapshot replay.

## Global Constraints

- Keep exact catalog matching; never fuzzy-select or silently substitute a vehicle trim.
- Missing evidence means unknown, not absent or false.
- Preserve existing safety gates and handoff rules unchanged.
- Keep raw and prefetched replay paths and artifacts separate.
- Final acceptance remains a fresh, unseeded 100-turn Qwen run with zero handoffs.
- Keep the current Java/Spring flow; do not migrate frameworks or rewrite the service wholesale.
- Candidate trim names are not fact evidence.

---

## File Map

- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/CatalogVehicleResolution.java` — add a validated unique-canonical-match outcome.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/CatalogVehicleResolver.java` — separate exact, unique-prefix, multiple-prefix, and missing outcomes.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java` — query facts using the resolved canonical trim.
- Modify `ford-ai-service/src/test/java/com/ford/service/ai/CatalogVehicleResolverTest.java` — lock all resolver outcomes.
- Modify `ford-ai-service/src/test/java/com/ford/service/ai/CatalogToolsTest.java` — prove canonical trim is passed to fact lookup.
- Modify `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java` only if regression tests show clarification is still blocked at the evidence gate.
- Verify `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java` against the validated snapshot; change only the forced-live configuration/assertions if required.

### Task 1: Define unique canonical resolution

**Interfaces:** `CatalogVehicleResolution` adds `UNIQUE_CANONICAL_MATCH`, which requires one `exact` canonical identity and an empty `choices` list. `EXACT` remains an exact normalized name match. `AMBIGUOUS` requires multiple canonical choices.

- [ ] **Step 1: Add failing resolver tests**

In `CatalogVehicleResolverTest`, add a case for `Ranger 2027 XL` against the existing entries. Assert `UNIQUE_CANONICAL_MATCH` and the canonical identity `RANGER / 2027 / XL Gasolina 4x2`. Keep the existing XLT test asserting two exact choices. Add a prefix with no candidate and assert `MISSING`.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run from `ford-ai-service`:

```bash
./gradlew test --tests com.ford.service.ai.CatalogVehicleResolverTest
```

Expected: the new unique-prefix assertion fails because the resolver currently returns `AMBIGUOUS` for any non-empty prefix list.

- [ ] **Step 3: Implement the typed resolver outcome**

Update `CatalogVehicleResolution` constructor validation so `EXACT` and `UNIQUE_CANONICAL_MATCH` each require exactly one canonical identity and no choices; `AMBIGUOUS` requires at least two choices and no exact identity. In `CatalogVehicleResolver.resolve`, keep exact matches first. For prefix matches, return `MISSING` for zero, `UNIQUE_CANONICAL_MATCH` for one, and `AMBIGUOUS` for more than one. Use only normalized whole-token prefix matching already defined in the resolver; do not add fuzzy scoring.

- [ ] **Step 4: Run resolver tests**

Run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogVehicleResolverTest
```

Expected: PASS; XLT remains ambiguous, XL resolves only to its sole canonical identity, and absent trims stay missing.

### Task 2: Query facts with canonical identity

**Interfaces:** `CatalogTools.getVehicle(model, year, trim)` must pass the resolver's canonical trim to `VehicleLookup` for `UNIQUE_CANONICAL_MATCH`. For `AMBIGUOUS`, it returns canonical choices and does not call `catalog.find` for any choice.

- [ ] **Step 1: Add failing catalog-tool tests**

In `CatalogToolsTest`, configure the fake catalog name index with the three Ranger 2027 rows from `CatalogVehicleResolverTest`. Call `getVehicle("Ranger", 2027, "XL")`; assert the catalog's last lookup uses `XL Gasolina 4x2` and the returned object is the exact canonical vehicle evidence. Call with `XLT`; assert the result contains both canonical XLT choices and no fact lookup occurs.

- [ ] **Step 2: Run the focused test and confirm failure**

Run from `ford-ai-service`:

```bash
./gradlew test --tests com.ford.service.ai.CatalogToolsTest
```

Expected: XL lookup fails because the tool currently sends the short trim unchanged or returns the partial-result object.

- [ ] **Step 3: Use the resolved canonical trim**

In `CatalogTools.getVehicle`, retain the `CatalogVehicleResolution`. Return `AmbiguousVehicleTrim` only for `AMBIGUOUS`; for `UNIQUE_CANONICAL_MATCH`, replace the requested trim with `resolution.exact().trim()` before calling `catalog.find(new VehicleLookup(...))`. Preserve existing missing/model-year responses.

- [ ] **Step 4: Run catalog-tool tests**

Run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogToolsTest
```

Expected: PASS; unique resolution queries only the canonical trim and ambiguous resolution selects none.

### Task 3: Verify evidence and clarification boundaries

- [ ] **Step 1: Add a regression assertion for multi-choice clarification**

In `SpringAiChatCompletionAdapterTest`, retain the existing `resolvesAmbiguousVehicleTrimIntoCanonicalClarificationWithoutTreatingChoicesAsFacts` test. Strengthen it to assert the final response kind is `CLARIFICATION`, the text lists only the two canonical options, and evidence remains `READ_ONLY_TOOL_RETURNED_EMPTY`. Do not count names as fact evidence.

- [ ] **Step 2: Run the focused adapter test**

Run:

```bash
./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest
```

Expected: PASS; clarification stays valid without vehicle facts. If it fails, change only the response-kind/evidence transition required to preserve `CLARIFICATION`; do not relax factual-evidence checks for `ANSWER`.

### Task 4: Verify the replay regression

- [ ] **Step 1: Run unit-test gate**

Run:

```bash
./gradlew test
```

Expected: all unit tests pass before integration work.

- [ ] **Step 2: Run focused catalog snapshot integrations**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.catalog.CatalogSnapshotParityIntegrationTest --tests com.ford.service.catalog.CatalogToolParityIntegrationTest
```

Expected: PASS against the configured disposable validated snapshot; no deployed catalog writes.

- [ ] **Step 3: Run seeded 100-turn diagnostic replay**

Run from `ford-ai-service`, with the validated snapshot export available at the existing configured path:

```bash
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
FORD_QWEN_REPLAY_SEED_THROUGH_TURN=72 \
FORD_QWEN_REPLAY_FORCE_LIVE_TURNS=5,73 \
FORD_QWEN_REPLAY_SEED_TRANSCRIPT=build/reports/local-qwen/100-turn-prefetched-qwen-transcript-ddcbfdb1239643c2ae092890f5d9f89a.md \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_LOCAL_CATALOG_EXPORT_DIR=/Users/miguelangeldelgadillozarate/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate \
./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Turn 74 is live because seeding ends at 72. Preserve the snapshot export path and do not set any seed variable for final acceptance.

Expected: replay gets beyond turn 74; XLT clarification remains canonical; XL fact lookup uses `XL Gasolina 4x2`. Record any missing box measurement as unknown rather than inventing it.

- [ ] **Step 4: Run fresh unseeded acceptance only after the diagnostic gate passes**

Run from `ford-ai-service` with all seed variables removed:

```bash
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_LOCAL_CATALOG_EXPORT_DIR=/Users/miguelangeldelgadillozarate/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate \
./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected: 100 committed turns and zero handoffs. Do not claim acceptance from the seeded run.
