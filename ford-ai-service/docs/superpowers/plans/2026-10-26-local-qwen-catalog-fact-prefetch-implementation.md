# Local Qwen Catalog-Fact Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the opt-in synthetic prefetched Qwen replay complete 100 turns only when every catalog-fact answer has positive evidence from that same turn.

**Architecture:** Keep raw replay routing unchanged. In prefetch mode, resolve a single exact vehicle identity from the current buyer message or the nearest prior buyer message in conversation memory; never resolve identity from assistant text. Reuse `CatalogTools.getVehicle`, pass bounded returned evidence to Qwen, and label its trace `REPLAY_PREFETCH`. Tighten the shared replay safety check to evaluate evidence from the current turn only.

**Tech Stack:** Java, Spring Boot integration-test configuration, JUnit 5, AssertJ, Mockito, local Qwen/Ollama, disposable PostgreSQL catalog fixture.

## Global Constraints

- All changes stay in integration-test replay code and synthetic local test data.
- No production writes, customer delivery, or hosted AI.
- Qwen writes customer-facing text for prefetched turns; prefetch supplies evidence only.
- Existing inventory, scope, and response safety checks remain enabled. No safety gate is weakened to make the replay pass.
- Prefetched evidence has an explicit `REPLAY_PREFETCH` origin and never counts as Qwen-selected tool use.
- Ambiguous vehicle identity, missing catalog results, or empty evidence fail closed.

---

## File Map

- Create `src/integrationTest/java/com/ford/service/conversations/LocalBuyerVehicleResolver.java` to extract buyer-only history and resolve one exact current-catalog vehicle identity.
- Create `LocalBuyerVehicleResolverTest.java` for exact, nearest-message, assistant-text exclusion, ambiguity, and no-match cases.
- Create `LocalReplayVehicleFactPrefetcher.java` to call exact `CatalogTools.getVehicle` and return usable vehicle evidence only.
- Create `LocalReplayVehicleFactPrefetcherTest.java` to verify exact calls and fail-closed results.
- Create `LocalReplayOfferFollowUp.java` and test for explicit `OFFERS` routing or narrow buyer phrasing about a previously discussed amount/benefit.
- Create `LocalReplayPromotionPrefetcher.java` and test to resolve exact buyer-context vehicle identity and call exact model/year `CatalogTools.listPromotions`.
- Modify `ReplayPrefetchingChatCompletionPort.java` and its test to inject comparison, vehicle-fact, or promotion evidence in distinct marked blocks and trace origins.
- Modify `LocalQwenComparisonPrefetchConfiguration.java` and its test to register replay-only evidence prefetchers only when prefetch mode is enabled.
- Modify `LocalReplaySafetyAssertions.java` and `LocalReplaySafetyAssertionsTest.java` so each turn must carry its own positive evidence and prefetch origin is accepted only in prefetch mode.
- Modify `QwenTwoTurnWebhookReplayIntegrationTest.java` to remove consecutive-turn evidence pooling and evaluate only that turn's trace events.
- Modify transcript writer and tests only if needed to distinguish a committed reply that failed groundedness validation from an uncommitted turn.

## Interfaces

`LocalBuyerVehicleResolver.resolve(String currentMessage, String memoryContext, List<VehicleNameIndexEntry> entries)` returns `Optional<VehicleRef>`. `VehicleRef` contains exact `model`, `year`, and `trim` from the catalog name index.

`LocalReplayVehicleFactPrefetcher.prefetch(String currentMessage, String memoryContext)` returns `Optional<PrefetchedVehicleFact>`. It uses exact identity with `CatalogTools.getVehicle(model, year, trim)` and returns only a matching `VehicleEvidence` with nonempty fact/source content. It must not treat `UnavailableModelYear`, `null`, or a mismatched identity as evidence.

`LocalReplayOfferFollowUp.matches(String message)` detects explicit offers routing and narrow buyer phrases such as “ese monto corresponde” without using assistant text to resolve the vehicle. `LocalReplayPromotionPrefetcher.prefetch(String currentMessage, String memoryContext)` uses the exact buyer-resolved model/year and returns only matching positive promotion evidence with source/conditions.

`ReplayPrefetchingChatCompletionPort.complete(request, context)` keeps model-selected calls and prefetch calls separately traceable. Qwen receives an evidence block in `AssistantRequest.memoryContext`; all other request fields and `ToolContext` remain unchanged.

### Task 1: Resolve Exact Vehicle From Buyer Context

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalBuyerVehicleResolver.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalBuyerVehicleResolverTest.java`
- Reuse: `src/main/java/com/ford/service/catalog/api/VehicleNameIndexEntry.java`

- [ ] **Step 1: Write failing resolver tests**

Use this request fixture so the current message is implicit and the prior buyer line is the only exact vehicle identity:

```java
var memory = "[HISTORIAL DE CONVERSACIÓN]\nHistorial reciente:\n"
        + "- usuario: Revisemos Ranger 2027 XLT Gasolina 4x2.\n"
        + "- asistente: El catálogo confirma Ranger 2027 XLT Gasolina 4x2.\n"
        + "[FIN DEL HISTORIAL]";
var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

assertThat(resolver.resolve("¿Qué fecha tiene el precio?", memory, entries))
        .contains(new LocalBuyerVehicleResolver.VehicleRef("RANGER", 2027, "XLT GASOLINA 4X2"));
```

Also test: current buyer message wins when it names one exact entry; a generic model/year index entry cannot make a longer exact trim match ambiguous; assistant-only identity does not resolve; the nearest buyer line with two equally specific exact entries is ambiguous; blank/no-match input returns empty; case and repeated whitespace normalize without partial trim matching.

- [ ] **Step 2: Run resolver tests and verify red**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalBuyerVehicleResolverTest`

Expected: compile or assertions fail because the resolver is not implemented.

- [ ] **Step 3: Implement buyer-only exact resolution**

Extract only `- usuario:` lines from the `[HISTORIAL DE CONVERSACIÓN]` block. Check the current message first. Prefer the longest complete normalized catalog identity so a generic model/year row does not conflict with its exact trim; if multiple distinct identities tie for longest match, return empty and do not fall back to history. Only when the current message has no exact identity, scan buyer lines newest-first and apply the same longest-match rule to the first line containing a catalog identity. Never inspect `- asistente:` lines, session summaries, or semantic candidates.

```java
Optional<VehicleRef> resolve(String currentMessage, String memoryContext,
                             List<VehicleNameIndexEntry> entries);
```

- [ ] **Step 4: Run resolver tests and verify green**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalBuyerVehicleResolverTest`

Expected: all resolver tests pass.

### Task 2: Prefetch Exact Vehicle-Fact Evidence

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayVehicleFactPrefetcher.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayVehicleFactPrefetcherTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayOfferFollowUp.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayOfferFollowUpTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPromotionPrefetcher.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPromotionPrefetcherTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayCatalogFactFollowUp.java` and its test for narrow factual wording missed by deterministic intent routing.
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayQwenAssistantService.java` to route every opt-in prefetched replay turn through Qwen.
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayQwenAssistantServiceTest.java` to prove non-comparison facts route through Qwen.
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayAssistantConfiguration.java` to remove deterministic-answer delegation in prefetch mode.
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfigurationTest.java`
- Reuse: `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`
- Reuse: `src/main/java/com/ford/service/catalog/api/VehicleEvidence.java`

- [ ] **Step 1: Write failing prefetcher tests**

Mock `CatalogTools` and verify exact identity and fail-closed outcomes:

```java
when(catalogTools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
        .thenReturn(vehicleEvidence("RANGER", 2027, "XLT GASOLINA 4X2"));

assertThat(prefetcher.prefetch("¿Qué fecha tiene el precio?", memory)).isPresent();
verify(catalogTools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
```

Also verify there is no catalog call for unresolved/ambiguous identity and no evidence for `null`, `UnavailableModelYear`, identity mismatch, or empty facts and sources.

- [ ] **Step 2: Run prefetcher tests and verify red**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayVehicleFactPrefetcherTest`

Expected: compile or assertions fail because the prefetcher is not implemented.

- [ ] **Step 3: Implement exact catalog lookup**

Resolve with `LocalBuyerVehicleResolver`. Call `CatalogTools.getVehicle(model, year, trim)` only for one exact identity. Accept only a `VehicleEvidence` whose model, year, and trim match the requested identity and which contains at least one fact or source URL. Do not replace an empty lookup with a broader search.

```java
Optional<PrefetchedVehicleFact> prefetch(String currentMessage, String memoryContext);
```

- [ ] **Step 4: Extend the replay completion decorator**

Run existing exact comparison prefetch first. If it does not resolve, run vehicle-fact prefetch for explicit `VEHICLE_DETAILS` routing. For explicit `OFFERS` routing or `LocalReplayOfferFollowUp.matches(request.message())`, run exact model/year promotion prefetch using buyer-only identity. Inject bounded evidence into dedicated vehicle or promotion blocks; preserve promotion conditions and sources. Record `get_vehicle` or `list_promotions` with `REPLAY_PREFETCH` origin and classify every `replay_prefetch_*` reason as prefetch. Keep model-origin traces unchanged. Do not inject evidence exceeding the 16,000-character cap. All prefetch beans remain behind `ford.test.local-qwen-comparison-prefetch.enabled=true`; raw mode has none.

- [ ] **Step 5: Test decorator routing and evidence origin**

Test exact buyer-identity offer detection, promotion lookup, source/conditions retention, and fail-closed ambiguity/empty results. Test vehicle and offer requests pass bounded evidence and original context to Qwen, preserve decision/trace/tenant/conversation/`ToolContext`, and record separate `REPLAY_PREFETCH` events. Comparison prefetch must still use only `compare_trims`. Configuration tests prove raw mode has no prefetch beans and opt-in mode registers them.

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayVehicleFactPrefetcherTest --tests com.ford.service.conversations.LocalReplayOfferFollowUpTest --tests com.ford.service.conversations.LocalReplayPromotionPrefetcherTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest --tests com.ford.service.conversations.LocalQwenComparisonPrefetchConfigurationTest`

Expected: all focused prefetch tests pass.

### Task 3: Require Current-Turn Grounding

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertions.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayTurnEvidence.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayTurnEvidenceTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java` only for accurate committed-failure labeling.
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java` only if transcript failure labeling changes.

- [ ] **Step 1: Add tests proving evidence must be from this turn**

Test `LocalReplayTurnEvidence.forTurn` with prior positive evidence and an empty current turn; assert current turn fails grounding. For offer follow-ups, require same-turn positive returned `REPLAY_PREFETCH/list_promotions` evidence; vehicle evidence alone must fail. Prefetch evidence passes only in prefetch mode; the same event fails raw mode. Keep comparison, inventory, and scope checks unchanged.

```java
assertThat(LocalReplaySafetyAssertions.evaluate(turn, answer, List.of(), options,
        CURRENT_ENTRIES, approvedTexts, true))
        .contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
```

- [ ] **Step 2: Run safety tests and verify red**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalReplayTurnEvidenceTest`

Expected: new origin/current-turn cases fail before implementation.

- [ ] **Step 3: Remove cross-turn evidence pooling**

In `runNaturalBuyerReplay`, retain exact provider-message evidence selection. In safety validation, offer follow-ups require positive same-turn `list_promotions` evidence; `get_vehicle` cannot substitute. Allow replay-prefetch events only in prefetch mode and preserve model-origin, comparison, inventory, and choice rules.

- [ ] **Step 4: Record post-commit safety failures accurately**

When a response is committed but safety validation fails, make the transcript state that the reply committed and validation failed. Keep the failure category and tool origin. Do not describe the turn as uncommitted. Add a focused writer test for this case.

- [ ] **Step 5: Run replay-safety and transcript tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalReplayTurnEvidenceTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest`

Expected: all safety and transcript tests pass; raw mode still rejects all replay-prefetch evidence.

### Task 4: Verify Prefetch Replay and Preserve Raw Baseline

**Files:**
- Verify: all files above; do not modify production source or production prompts.
- Verify: artifacts under `build/reports/local-qwen/` and the matching Gradle integration-test XML.

- [ ] **Step 1: Run all unit tests first**

Run: `./gradlew test --rerun-tasks`

Expected: `BUILD SUCCESSFUL` before integration verification.

- [ ] **Step 2: Run focused integration tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalBuyerVehicleResolverTest --tests com.ford.service.conversations.LocalReplayVehicleFactPrefetcherTest --tests com.ford.service.conversations.LocalReplayPromotionPrefetcherTest --tests com.ford.service.conversations.LocalReplayOfferFollowUpTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalReplayTurnEvidenceTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest --tests com.ford.service.conversations.LocalQwenComparisonPrefetchConfigurationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest`

Expected: all selected tests pass without hosted AI or production writes.

- [ ] **Step 3: Run prefetched local 100-turn replay**

Run from `ford-ai-service`:

```bash
FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
./gradlew integrationTest --rerun-tasks --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected for success: transcript says `100/100 turns complete`; all 100 turns pass current-turn evidence, comparison scope, inventory, and choice checks. If it stops, preserve the strict gate and report the exact first failing turn and reason; do not claim completion.

- [ ] **Step 4: Confirm raw path remains separate**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenComparisonPrefetchConfigurationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest`

Verify that raw mode has no decorator and no `REPLAY_PREFETCH` evidence appears in raw traces. Do not run the raw 100-turn replay as a required success criterion; its earlier result remains a separate known failure at turn 25 and raw Qwen routing is out of scope for this fix.

## Self-Review

- Exact-context resolution, evidence injection, trace origin, per-turn evidence selection, failure labeling, focused tests, and full prefetched replay each have a task.
- Raw replay does not receive prefetch data; its provider routing is unchanged.
- No production source, delivery configuration, or hosted AI setting is changed.
- Empty, ambiguous, mismatched, or oversized evidence never becomes accepted evidence.

### Task 5: Ground Promotion Applicability Follow-Ups

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayOfferFollowUp.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayOfferFollowUpTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPromotionPrefetcher.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPromotionPrefetcherTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfigurationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertions.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`

- [ ] **Step 1: Test offer-follow-up recognition and exact promotion lookup**

Tests must recognize `¿Ese monto corresponde a la XLT que acabamos de revisar?` and explicit `ToolFamily.OFFERS` routing. Reject unrelated factual messages. `CatalogTools.listPromotions` orders `offset` before `limit`; mock `listPromotions("RANGER", 2027, null, false, 0, 20, "full", "RANGER 2027")` and assert returned evidence retains promotion conditions and source. Assert no lookup when exact buyer vehicle is missing/ambiguous, and no accepted result for empty/mismatched promotion results. In local-snapshot preflight, assert the real exact buyer-history lookup returns promotion evidence.

- [ ] **Step 2: Verify RED**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayOfferFollowUpTest --tests com.ford.service.conversations.LocalReplayPromotionPrefetcherTest`

Expected: test compilation fails because these replay-only classes are absent.

- [ ] **Step 3: Implement replay-only promotion evidence injection**

Have the replay-only completion decorator retain the last 20 buyer messages per conversation; never store assistant text. Use that buyer-only history for exact current-catalog identity resolution so memory compaction cannot erase a buyer-stated trim. Call `listPromotions(model, year, null, false, 0, 20, "full", model + " " + year)`; year must appear in query because `CatalogTools` only honors `modelYear` when query contains it. The catalog's substring model search may return related model names, so inject only positive results matching the exact resolved model and year; reject if no exact results. On explicit `OFFERS` routing or recognized offer-follow-up wording, inject bounded evidence under `[REPLAY PREFETCHED PROMOTION EVIDENCE]`. Preserve source URL and conditions. Trace `list_promotions` with a `replay_prefetch_*` reason. Update trace decoding so every reason beginning `replay_prefetch_` is `REPLAY_PREFETCH`, including existing vehicle prefetch. Keep all beans behind the prefetch-only configuration.

Route every synthetic turn through local Qwen when opt-in prefetched replay mode is enabled. The deterministic standard answer path must not write prefetched replay replies; raw mode remains unchanged.

For vehicle facts, run `get_vehicle` prefetch for `VEHICLE_DETAILS` routing or a narrow replay-only factual-question detector, including source/citation follow-ups such as “¿Me compartes de dónde sale ese dato del motor?”. This covers fixture questions the router classifies `GENERAL/NONE`, while preserving Qwen as reply writer and requiring strict current-turn grounding evidence.

- [ ] **Step 4: Require offer evidence for promotion follow-ups**

For a recognized promotion follow-up in `CATALOG_FACT`, safety accepts only same-turn `list_promotions` evidence with returned outcome and positive count. `get_vehicle` evidence alone fails. Raw mode rejects the prefetch event. Add tests for all three outcomes.

- [ ] **Step 5: Verify focused tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayOfferFollowUpTest --tests com.ford.service.conversations.LocalReplayCatalogFactFollowUpTest --tests com.ford.service.conversations.LocalReplayPromotionPrefetcherTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest --tests com.ford.service.conversations.LocalQwenComparisonPrefetchConfigurationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalReplayQwenAssistantServiceTest`

Expected: `BUILD SUCCESSFUL`; raw routing remains unwrapped, Qwen remains response writer, and offer evidence is separately traced.

- [ ] **Step 6: Re-run final Task 4 verification**

Run unit tests, all focused integration tests, then the exact local prefetched 100-turn command from Task 4. Do not claim completion unless transcript records 100/100 grounded turns. Preserve first failure and exact reason otherwise.
