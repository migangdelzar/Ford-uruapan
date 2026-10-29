# Local Qwen Comparison Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separate local replay mode that prefetches exact comparison evidence, lets Qwen write the answer, and keeps raw Qwen tool-selection results honest.

**Architecture:** Add test-only wiring enabled only by a replay property. It resolves exactly two named same-model/year trims, calls existing `CatalogTools.compareTrims` in bounded summary mode, and appends structured evidence before local Qwen writes the answer. Exact comparison turns bypass the deterministic catalog-answer fast path only in prefetch replay; all other turns keep the normal path. Trace origin distinguishes prefetch from Qwen-selected tools; raw and prefetch transcripts remain separate.

**Tech Stack:** Java 17, Spring Boot test configuration, Gradle `integrationTest`, JUnit 5, AssertJ, local Qwen/Ollama, disposable PostgreSQL catalog fixture.

## Global Constraints

- Changes stay in `src/integrationTest`; do not change production Java, prompts, delivery, or hosted-provider configuration.
- Raw replay remains opt-in and does not accept prefetch evidence.
- Prefetch mode is opt-in with `FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true` and uses only local synthetic data.
- Never claim Qwen selected `compare_trims` when the replay harness selected it.
- Require exactly two exact trims for one model and year; ambiguous or empty lookup fails closed.
- Keep the existing inventory, comparison-scope, and evidence safety checks enabled.

---

### Task 1: Resolve Exact Comparison Pairs

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalComparisonPairResolver.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalComparisonPairResolverTest.java`
- Reuse: `src/main/java/com/ford/service/catalog/api/VehicleNameIndexEntry.java`

**Interfaces:**
- Produces `Optional<LocalComparisonPairResolver.Pair> resolve(String message, List<VehicleNameIndexEntry> entries)`.
- `Pair` contains exact `model`, `year`, and two exact `trims`.
- Only exact text matches; only two distinct entries with the same model/year qualify.

- [x] **Step 1: Write resolver tests and compile-only API skeleton**

Test exact Ranger 2027 XL/XLT pair resolution, case-insensitive matching, unrelated models, one trim, three trims, and mixed year/model rejection. Example:

```java
var entries = List.of(
        new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
        new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

assertThat(resolver.resolve(
        "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2", entries))
        .contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
```

Create only enough API for this test to compile; leave resolution empty for the red run:

```java
final class LocalComparisonPairResolver {
    Optional<Pair> resolve(String message, List<VehicleNameIndexEntry> entries) {
        return Optional.empty();
    }

    record Pair(String model, int year, List<String> trims) { }
}
```

- [x] **Step 2: Run resolver tests and confirm expected failures**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalComparisonPairResolverTest`

Expected: resolver test compiles and fails its assertion because the skeleton returns `Optional.empty()`.

- [x] **Step 3: Implement normalized exact matching**

Normalize case and repeated whitespace only. Match each complete `model + year + trim` name in the message; return a pair only when exactly two entries match and both share model/year. Do not use semantic search or partial trim matching.

- [x] **Step 4: Run resolver tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalComparisonPairResolverTest`

Expected: all resolver tests pass.

### Task 2: Prefetch Comparison Evidence

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayComparisonPrefetcher.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayComparisonPrefetcherTest.java`
- Reuse: `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`
- Reuse: `src/main/java/com/ford/service/ai/application/CatalogNameIndexService.java`

**Interfaces:**
- Consumes `LocalComparisonPairResolver`, current catalog names, and `CatalogTools`.
- Produces `Optional<PrefetchedComparison> prefetch(String message)`.
- `PrefetchedComparison` contains the exact pair, `CatalogTools.TrimComparison`, and positive evidence count.

- [x] **Step 1: Write prefetch tests and compile-only API skeleton**

Verify exact pairs call `compareTrims(model, year, trims, "summary")` once; unresolved pairs do not call it; empty or incomplete comparison results produce no prefetch evidence. Summary keeps the injected evidence bounded while retaining prices and source-backed differences. Mock `CatalogTools` here to verify exact arguments; the opt-in replay later exercises the real local tool bean and catalog.

Declare `PrefetchedComparison` with the fields in the interface section and start `prefetch(String)` by returning `Optional.empty()` so test compiles and fails behaviorally.

- [x] **Step 2: Run prefetch tests and confirm expected failures**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayComparisonPrefetcherTest`

Expected: prefetch tests compile and fail because the skeleton returns `Optional.empty()` without calling `compareTrims`.

- [x] **Step 3: Implement exact local prefetch**

Resolve via `CatalogNameIndexService.currentEntries()`. Call the existing public `CatalogTools.compareTrims` in `summary` mode only for a valid pair. Preserve empty evidence and never substitute `list_promotions`, `get_vehicle`, or semantic candidates.

- [x] **Step 4: Run prefetch tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayComparisonPrefetcherTest`

Expected: all prefetch tests pass.

### Task 3: Add Replay-Only Chat Completion Decorator

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayAssistantConfiguration.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayQwenAssistantService.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayQwenAssistantServiceTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfigurationTest.java`
- Create: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Reuse: `src/main/java/com/ford/service/ai/application/port/out/ChatCompletionPort.java`
- Reuse: `src/main/java/com/ford/service/ai/api/AssistantRequest.java`

**Interfaces:**
- Decorator implements `ChatCompletionPort` and wraps the existing `chatCompletionPort` bean only when `ford.test.local-qwen-comparison-prefetch.enabled=true`.
- Append a bounded `[REPLAY PREFETCHED COMPARISON EVIDENCE]` JSON block to `AssistantRequest.memoryContext`; preserve all other request fields and `ToolContext` unchanged.
- Record `DecisionTraceEvent(stage="tool", reasonCode="replay_prefetch_comparison", toolName="compare_trims", outcome="returned", evidenceCount=1)` when exact comparison evidence is prefetched.

- [x] **Step 1: Write decorator tests and compile-only delegate skeleton**

Verify that a supported comparison request sends evidence to the delegate, preserves message/tenant/conversation/decision/trace/tool context, and records one prefetch event. Verify non-comparison and unresolved requests delegate unchanged without a prefetch event.

Start with a wrapper whose `complete(request, context)` only calls `delegate.complete(request, context)`; keep constructor dependencies and return type real so tests fail on missing prefetch behavior, not compilation.

- [x] **Step 2: Run decorator tests and confirm expected failures**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest`

Expected: decorator tests compile and fail because the skeleton delegates without adding evidence or a trace event.

- [x] **Step 3: Implement the decorator**

Create a copied `AssistantRequest` with only `memoryContext` extended by the marked serialized evidence. Delegate Qwen generation; do not render or send a deterministic answer.

- [x] **Step 4: Add conditional test wiring**

Use an integration-test `BeanPostProcessor` conditioned on `ford.test.local-qwen-comparison-prefetch.enabled`. Wrap only the bean named `chatCompletionPort`; when the property is false, leave the raw provider unchanged. In prefetch mode, route only exact comparison-pair requests to a second `SpringAiAssistantService` with the deterministic answer fast path disabled; all other turns use the normal assistant. Register the property from `FORD_QWEN_COMPARISON_PREFETCH_ENABLED` in `@DynamicPropertySource`.

- [x] **Step 5: Run decorator and raw-mode wiring tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest`

Expected: focused tests pass; raw mode has no decorator bean and prefetch mode has exactly one wrapper.

### Task 4: Preserve Evidence Origin in Transcript and Safety Gate

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertions.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Reuse: `src/main/java/com/ford/service/ai/application/DecisionTraceEvent.java`

**Interfaces:**
- Add `ToolTraceOrigin { MODEL, REPLAY_PREFETCH }` to replay trace metadata. Existing three-argument trace constructors default to `MODEL`.
- Keep the existing `evaluate(...)` method strict for raw mode. Add a prefetch-aware overload that accepts a comparison trace only when `origin == REPLAY_PREFETCH` and prefetch mode is explicitly enabled.

- [x] **Step 1: Write failing origin/safety tests**

Test that raw mode rejects a `REPLAY_PREFETCH` comparison trace; prefetch mode accepts a returned `compare_trims` prefetch trace; missing/empty prefetch evidence still fails. Test markdown shows a separate `Replay prefetch` section, not under model-selected tools.

- [x] **Step 2: Run tests and confirm expected failures**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest`

Expected: tests compile and fail because raw mode accepts the new origin or transcript does not separate it yet.

- [x] **Step 3: Implement origin-aware tracing and rendering**

Select `reason_code` with tool events in `toolTraceFor`; map `replay_prefetch_comparison` to `REPLAY_PREFETCH`. Render prefetched events in their own transcript section and leave Qwen-selected tool listing unchanged.

- [x] **Step 4: Keep raw and prefetch safety paths distinct**

The raw evaluator accepts only model-origin comparison tools. The prefetch evaluator may accept one successful prefetch comparison event; all existing scope, inventory, option-count, and evidence checks remain active.

- [x] **Step 5: Run focused trace and safety tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest`

Expected: all tests pass; raw mode still rejects prefetch-only evidence.

### Task 5: Separate Opt-In Replay Artifacts and Verify

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`

**Interfaces:**
- Add an explicit replay mode (`RAW_QWEN` or `PREFETCHED_QWEN`) to transcript metadata and artifact names.
- Raw invocation remains unchanged. Prefetch invocation requires both `FORD_QWEN_REPLAY_ENABLED=true` and `FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true`.

- [x] **Step 1: Test transcript mode separation**

Verify raw artifact header/status remains `UNVERIFIED_RAW_QWEN`; prefetch artifact says `PREFETCHED_QWEN` and uses a distinct path; both keep the same synthetic-only disclaimer and snapshot provenance.

- [x] **Step 2: Run transcript mode tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest`

Expected: tests fail before implementation and pass after mode metadata/path support.

- [x] **Step 3: Run focused replay test without prefetch**

Run: `FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate FORD_QWEN_REPLAY_ENABLED=true FORD_QWEN_100_REPLAY_ENABLED=true FORD_AI_CATALOG_NAME_INDEX_ENABLED=true ./gradlew integrationTest --rerun-tasks --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest`

Result: raw artifact stayed `UNVERIFIED_RAW_QWEN` and stopped at turn 25 after Qwen chose `list_promotions` and `get_vehicle`; comparison evidence remained missing. Gate unchanged.

- [x] **Step 4: Run opt-in prefetched replay**

Run the same command with `FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true` added. Result: full 100-turn prefetch replay stopped at turn 14 with `SAFE_FALLBACK_REVIEW`, before comparison turn 25. The separate direct local-Qwen comparison check passed: trace showed returned prefetch evidence and the existing comparison safety gate passed. Do not claim the 100-turn replay completed.

- [x] **Step 5: Run focused test suite and report both outcomes**

Verification: `./gradlew test --rerun-tasks` passed; all focused integration tests from Tasks 1–5 passed. Report raw and prefetched replay outcomes independently; never present prefetch as raw tool-selection success.

## Self-review

- Covers every spec goal: local-only mode, exact pair resolution, existing comparison tool reuse, Qwen-authored wording, separate raw/prefetch traces, fail-closed behavior, and separate outcomes.
- No production Java, prompt, dispatch, or delivery class is in the file list.
- Raw safety remains strict; prefetch evidence is accepted only with explicit mode and origin.
- Task signatures, property names, tool names, and artifact modes are consistent across tasks.
