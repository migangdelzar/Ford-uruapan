# Turn 29 Comparison Evidence Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the local prefetched Qwen replay reliably deliver exact comparison evidence to Qwen for turn 29 and report why comparison prefetch did or did not reach the model.

**Architecture:** Keep comparison resolution and `CatalogTools.compareTrims` in the replay-only prefetch path. Return bounded, reason-coded prefetch status separately from model-selected tool calls; inject only a valid exact-pair comparison into Qwen. Keep current safety checks authoritative and make the transcript retain a specific missing-evidence failure reason.

**Tech Stack:** Java, Spring Boot integration tests, JUnit 5, AssertJ, Mockito, local Qwen/Ollama, disposable PostgreSQL catalog snapshot.

## Global Constraints

- All changes stay in integration-test replay code and synthetic local test data.
- No production writes, customer delivery, or hosted AI.
- Qwen writes customer-facing text for prefetched turns; prefetch supplies evidence only.
- Existing inventory, scope, grounding, and response safety checks remain enabled. No safety gate is weakened to make the replay pass.
- Prefetched evidence has an explicit `REPLAY_PREFETCH` origin and never counts as Qwen-selected tool use.
- Ambiguous vehicle identity, missing catalog results, or empty evidence fail closed.
- Raw replay remains separate and unchanged.
- Completion requires 100/100 turns, zero handoffs, and all existing safety checks passing.

---

## File Map

- Modify `src/integrationTest/java/com/ford/service/conversations/LocalReplayComparisonPrefetcher.java`: expose exact-pair prefetch outcome without treating absent comparison data as equality.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalComparisonPairResolver.java` and `LocalComparisonPairResolverTest.java`: identify bounded comparison intent separately from ordinary vehicle questions.
- Modify `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`: record the bounded status for the current turn and inject only valid, bounded comparison evidence.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvidence.java`: keep current-turn status metadata beside positive evidence; never retain buyer/assistant text in diagnostics.
- Modify `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java` and `LocalReplayComparisonPrefetcherTest.java`: test turn 29 follow-up, outcome distinctions, and fail-closed branches.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java` and `QwenTwoTurnWebhookReplayIntegrationTest.java`: report the specific safe failure and status when a replay turn fails after a committed reply.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`: verify safe comparison diagnostics in partial reports.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java` only if the regression exposes a mismatch; preserve the existing comparison evidence gate.

## Data Interfaces

Add this replay-only result contract to `LocalReplayComparisonPrefetcher`:

```java
enum Status { NOT_A_COMPARISON, PAIR_UNRESOLVED, CATALOG_RESULT_INCOMPLETE, READY }

record PrefetchResult(Status status, PrefetchedComparison comparison) {
    PrefetchResult {
        if ((status == Status.READY) != (comparison != null)) {
            throw new IllegalArgumentException("READY requires a comparison; other statuses must not carry one");
        }
    }
}
```

`NOT_A_COMPARISON` means the current buyer message is not a comparison request/follow-up. `READY` means both exact trims and one comparison pair were returned. It does **not** mean safety facts exist. An empty safety fact set remains unknown. `ReplayPrefetchingChatCompletionPort` records one bounded current-turn diagnostic status: `PAIR_UNRESOLVED`, `CATALOG_RESULT_INCOMPLETE`, `EVIDENCE_TOO_LARGE`, `INJECTED`, or `NOT_A_COMPARISON`. Evidence text and customer messages are excluded from status metadata.

### Task 1: Make Prefetch Outcomes Explicit — Complete

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayComparisonPrefetcher.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayComparisonPrefetcherTest.java`

**Interfaces:**
- Consumes: `LocalComparisonPairResolver.resolveFollowUp(String, String, List<VehicleNameIndexEntry>)` and `CatalogTools.compareTrims(String, Integer, List<String>, String)`.
- Produces: `PrefetchResult prefetchResult(String message, String priorBuyerMessages)` with the status contract above. Add `LocalComparisonPairResolver.isComparisonIntent(String message)` using bounded comparison verbs/questions and the existing follow-up vocabulary to distinguish an ordinary turn (`NOT_A_COMPARISON`) from an unresolved comparison intent (`PAIR_UNRESOLVED`). Keep the existing `Optional<PrefetchedComparison> prefetch(...)` overloads as wrappers so existing callers remain source-compatible.

- [x] **Step 1: Add failing outcome tests**

Add tests asserting: an ordinary price question with no pair intent returns `NOT_A_COMPARISON`; a comparison follow-up with no exact buyer pair returns `PAIR_UNRESOLVED`; resolved pair with missing trim or a comparison count other than one returns `CATALOG_RESULT_INCOMPLETE`; resolved exact pair with a one-pair comparison returns `READY` and the same `PrefetchedComparison` object. Reuse the existing `ENTRIES` and `comparison(...)` fixtures in `LocalReplayComparisonPrefetcherTest`.

- [x] **Step 2: Verify RED**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayComparisonPrefetcherTest
```

Expected: compile failure because `prefetchResult` and `PrefetchResult` are not defined.

- [x] **Step 3: Implement the result contract**

Implement `prefetchResult` by resolving the pair first. If no pair resolves, return `PAIR_UNRESOLVED` when `isComparisonIntent(message)` is true; otherwise return `NOT_A_COMPARISON`. `isComparisonIntent` must recognize explicit compare requests and follow-ups such as “cada una” and “esas dos”, without treating an ordinary single-vehicle price question as a comparison. Call `compareTrims` only for the exact pair. Return `CATALOG_RESULT_INCOMPLETE` if comparison is null, any requested trim is missing, or the result does not contain exactly one pair. Otherwise return `READY` with the comparison and evidence count. Do not reject `READY` because its `differences` list is empty: an empty list means no source-backed differences, not equality.

Implement existing `prefetch` overloads as wrappers that return `Optional.of(result.comparison())` only for `READY`, preserving current behavior for existing callers.

- [x] **Step 4: Verify GREEN**

Run the same `LocalReplayComparisonPrefetcherTest` command. Expected: all resolver/prefetcher tests pass.

### Task 2: Record Status and Prove Turn 29 Evidence Reaches Qwen — Complete

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvidence.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`

**Interfaces:**
- Consumes: `LocalReplayComparisonPrefetcher.PrefetchResult`.
- Produces: a current-turn comparison status accessor in `LocalReplayPrecisionEvidence`; positive evidence remains available through `currentTurnEvidence(UUID)` and `currentTurnToolTraces(UUID)` exactly as today. Use enum `ComparisonPrefetchStatus { NOT_A_COMPARISON, PAIR_UNRESOLVED, CATALOG_RESULT_INCOMPLETE, EVIDENCE_TOO_LARGE, INJECTED }`.

- [x] **Step 1: Add the exact follow-up regression**

In `ReplayPrefetchingChatCompletionPortTest`, use one fixed `tenantId` and `conversationId`. Call the port first with buyer message `Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.`; then call it with `¿La comparación incluye alguna característica de seguridad para cada una?`. Mock `CatalogTools.compareTrims` with both exact trims, one pair result, and no safety-specific differences. Assert the second delegate request contains `[REPLAY PREFETCHED COMPARISON EVIDENCE]`, the second-turn collector contains returned positive `compare_trims` evidence with `REPLAY_PREFETCH` origin, and the delegate-generated cautious answer passes `LocalReplaySafetyAssertions` in prefetch mode. Assert no model-selected trace is fabricated by the prefetch.

- [x] **Step 2: Add failing status tests**

Assert each branch records the exact current-turn status: pair unresolved → `PAIR_UNRESOLVED`; missing catalog comparison → `CATALOG_RESULT_INCOMPLETE`; serialized comparison larger than `MAX_EVIDENCE_JSON_CHARS` → `EVIDENCE_TOO_LARGE`; bounded evidence appended to the request → `INJECTED`. Assert `beginTurn` clears the previous turn’s status and evidence. Assert status metadata contains no evidence JSON, buyer message, or assistant reply.

- [x] **Step 3: Verify RED**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest
```

Expected: compile/assertion failure for the new current-turn status API and status branches.

- [x] **Step 4: Wire explicit statuses and bounded injection**

Have `complete` call `prefetchResult`. Record `NOT_A_COMPARISON` when no exact/current or buyer-history comparison follow-up is present; record the result status for attempted prefetch. On `READY`, serialize once. If serialized length exceeds `MAX_EVIDENCE_JSON_CHARS`, record `EVIDENCE_TOO_LARGE` and delegate with the original request. Otherwise record positive precision evidence, append the existing marked evidence block, record the existing `replay_prefetch_comparison` tool trace, mark `INJECTED`, and delegate. Do not change the raw-mode bean configuration or model tool traces.

- [x] **Step 5: Verify GREEN**

Run the same `ReplayPrefetchingChatCompletionPortTest` command. Expected: all existing and new tests pass; exact buyer-history follow-up injects current-turn evidence; oversized or incomplete evidence remains fail-closed.

### Task 3: Preserve Specific Failure Reasons and Re-run Replay

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`

**Interfaces:**
- Consumes: current-turn prefetch status and `LocalReplaySafetyAssertions.Violation` values.
- Produces: a partial Markdown transcript that reports the safe failure category and bounded prefetch status while preserving the committed answer and tool-origin separation.

- [x] **Step 1: Add failing report tests**

Add a writer test for a committed comparison reply that fails with missing evidence. Assert the partial report shows turn number, `MISSING_CATALOG_EVIDENCE`, and the prefetch status, while it does not include serialized evidence or buyer-history content. Preserve the existing behavior that partial reports are never marked complete.

- [x] **Step 2: Verify RED**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest
```

Expected: compile/assertion failure because the report does not yet accept/render the diagnostic metadata.

- [x] **Step 3: Report the actual safety violation**

Add a specific `MISSING_CATALOG_EVIDENCE` failure category. Add a `recordFailure` overload that accepts nullable `LocalReplayPrecisionEvidence.ComparisonPrefetchStatus`; write that enum only when present. In the natural replay catch path, retrieve the current-turn status and preserve the safety violation name in the safe failure category instead of collapsing it into `INVALID_STATE`. Append only the status enum and violation name to the local transcript. Do not alter `LocalReplaySafetyAssertions` acceptance rules.

- [x] **Step 4: Verify focused suites**

Run from `ford-ai-service`:

```bash
./gradlew test
./gradlew integrationTest \
  --tests com.ford.service.conversations.LocalComparisonPairResolverTest \
  --tests com.ford.service.conversations.LocalReplayComparisonPrefetcherTest \
  --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest \
  --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest \
  --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest
```

Expected: both commands succeed. Raw-mode safety tests still reject prefetch evidence; prefetch comparison tests require a positive same-turn `compare_trims` trace.

- [ ] **Step 5: Run the opt-in prefetched 100-turn replay — In Progress**

Use the approved local export and snapshot. Run only after unit and focused integration suites pass:

```bash
FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
./gradlew integrationTest --rerun-tasks --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected: 100/100 committed turns, zero handoffs, all current safety assertions pass, and turn 29 transcript shows positive `REPLAY_PREFETCH / compare_trims` evidence. If the catalog comparison contains no safety facts, Qwen may state that the available comparison does not confirm them; it must not claim equality or invent safety differences. If the run fails, use the new safe status to identify the broken boundary; do not relax safety gates.

- [ ] **Step 6: Verify raw/prefetched separation**

Re-run `ReplayPrefetchingChatCompletionPortTest` and the raw-mode assertions in `LocalReplaySafetyAssertionsTest`. Confirm raw mode still has no replay-prefetch injection or accepted `REPLAY_PREFETCH` evidence. Do not run the raw 100-turn replay as a success requirement.

## Self-Review

- Every design requirement has a task: exact pair, Qwen evidence injection, status diagnostics, fail-closed behavior, safe report, unit/focused integration checks, full replay, and raw/prefetch separation.
- The plan does not treat missing safety facts as equality or as proof that the source database lacks them.
- Prefetch status does not turn prefetch into model-selected tool use.
- Every required verification command runs unit/focused checks before the slow local replay.
