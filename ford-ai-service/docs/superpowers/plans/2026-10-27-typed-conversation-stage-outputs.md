# Typed Conversation Stage Outputs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement validated, typed outputs across the Ford conversation flow, deterministic trim ambiguity handling, and then retry the complete replay flow.

**Architecture:** Keep the current Java/Spring pipeline. Reuse existing types where they already define a stage output; add explicit result types and validated transitions only where outcomes are currently implicit. Deterministic code owns scope, tool authorization, catalog identity, evidence, quality, and handoff decisions; model output remains constrained to classification/drafting.

**Tech Stack:** Java, Spring Boot, Spring AI tool calling, JUnit 5, AssertJ, Mockito, Gradle integration tests, PostgreSQL/Testcontainers replay.

## Global Constraints

- Keep exact catalog matching; never fuzzy-select or silently substitute a vehicle trim.
- Missing evidence means unknown, not absent or false.
- Preserve existing safety gates and handoff rules unchanged.
- Keep raw and prefetched replay separate; ordinary logs must not contain raw buyer messages, tool arguments, personal data, or secrets.
- Seeded replays are diagnostic only; final acceptance requires a fresh, unseeded 100-turn Qwen run with zero handoffs.
- Add contracts incrementally at existing Java service boundaries; do not migrate to LangGraph or rewrite the service wholesale.
- Run unit tests before integration tests and integration tests before the full replay.

---

## File Map

**Modify existing stage outputs and orchestration:**
- `src/main/java/com/ford/service/ai/application/InputSecurityPolicy.java` — secured-input result and explicit blocked/allowed state.
- `src/main/java/com/ford/service/ai/application/ConversationDecision.java` and `VehicleEntityResolution.java` — classification and vehicle-resolution outputs.
- `src/main/java/com/ford/service/ai/application/CatalogVehicleResolver.java` — resolve extracted vehicle mentions against the complete canonical catalog index.
- `src/main/java/com/ford/service/ai/application/AssistantScopePolicy.java` — explicit scope result instead of reason-string-only flow.
- `src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java` — immutable authorized tool set.
- `src/main/java/com/ford/service/ai/application/ToolResultTracker.java` — immutable per-tool execution snapshot.
- `src/main/java/com/ford/service/ai/application/EvidenceGroundedResponsePolicy.java` — explicit quality decision.
- `src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java` — validated stage transitions and early terminal outcomes.
- `src/main/java/com/ford/service/ai/application/DecisionTraceEvent.java` — bounded stage/outcome vocabulary.
- `src/main/java/com/ford/service/ai/api/AssistantResponse.java` and `AssistantTurn.java` — explicit response kind and final outcome while preserving compatibility constructors where callers need them.
- `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java` — exact/ambiguous/unavailable variant outcomes.
- `src/main/java/com/ford/service/ai/adapter/out/springai/ObservedToolCallback.java` — safe match-shape diagnostics only.
- `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java` — translate tool/evidence/quality results into response outcomes.

**Create focused result types:**
- `src/main/java/com/ford/service/ai/application/ScopeDecision.java` — `ALLOWED`/`DENIED` plus bounded reason.
- `src/main/java/com/ford/service/ai/application/AuthorizedToolSet.java` — immutable validated allowlist for one turn.
- `src/main/java/com/ford/service/ai/application/ToolExecutionSnapshot.java` — per-tool `RETURNED`/`EMPTY`/`FAILED` status and safe reason code.
- `src/main/java/com/ford/service/ai/application/EvidenceResult.java` — evidence `SUPPORTED`/`UNKNOWN`/`CONFLICTED` result and bounded source references.
- `src/main/java/com/ford/service/ai/application/QualityDecision.java` — `PASS`/`REPAIR`/`CLARIFY`/`HANDOFF`/`BLOCK` plus reason.
- `src/main/java/com/ford/service/ai/application/VehicleTrimCandidate.java` — canonical model/year/trim candidate for clarification choices.
- `src/main/java/com/ford/service/ai/application/CatalogVehicleResolution.java` — catalog-level `EXACT`/`AMBIGUOUS`/`MISSING` output, distinct from text entity extraction.
- `src/main/java/com/ford/service/ai/api/ResponseKind.java` — `ANSWER`/`CLARIFICATION`/`SAFE_FALLBACK` response classification.

**Test files:**
- `src/test/java/com/ford/service/ai/InputSecurityPolicyTest.java`
- `src/test/java/com/ford/service/ai/ConversationDecisionTest.java`
- `src/test/java/com/ford/service/ai/VehicleEntityResolverTest.java`
- `src/test/java/com/ford/service/ai/AssistantScopePolicyTest.java`
- `src/test/java/com/ford/service/ai/ToolAuthorizationPolicyTest.java`
- `src/test/java/com/ford/service/ai/ObservedToolCallbackTest.java`
- Create: `src/test/java/com/ford/service/ai/ToolResultTrackerTest.java`
- `src/test/java/com/ford/service/ai/CatalogToolsTest.java`
- `src/test/java/com/ford/service/ai/EvidenceGroundedResponsePolicyTest.java`
- `src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`
- `src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- `src/test/java/com/ford/service/conversations/DecisionTraceEventTest.java`
- `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- `src/integrationTest/java/com/ford/service/conversations/LocalCatalogSnapshotLoaderTest.java`

---

### Task 1: Make Security and Classification Outcomes Explicit

**Files:** `InputSecurityPolicy.java`, `ConversationDecision.java`, `SpringAiAssistantService.java`, and the matching tests above.

**Interfaces:** Reuse `InputSecurityPolicy.Result` and `ConversationDecision`; add an explicit `InputSecurityPolicy.Status { ALLOWED, BLOCKED }` derived from the existing result fields. Keep the existing `blocked()` accessor during migration. Classification output stays `ConversationDecision`, whose intent, tool family, evidence, next action, and vehicle resolution must remain validated.

- [x] **Step 1: Add tests for allowed and blocked security outcomes**
  Assert sanitized allowed input has `ALLOWED`; prompt-injection and sensitive-data blocks have `BLOCKED` and do not invoke `ConversationDecisionService` or the orchestrator.
- [x] **Step 2: Run the focused test and observe the missing status contract**
  Run `./gradlew test --tests com.ford.service.ai.InputSecurityPolicyTest --tests com.ford.service.ai.SpringAiAssistantServiceTest` from `ford-ai-service`; confirm new status assertions fail before implementation.
- [x] **Step 3: Add the explicit status without changing decisions**
  Add `Status status()` to `InputSecurityPolicy.Result`; derive it solely from `blocked`. Keep all existing fields and overloads. Add constructor validation so `reason == NONE` for allowed input and a non-`NONE` reason for blocked input.
- [x] **Step 4: Verify unit tests**
  Re-run the two test classes; existing input security behavior and early-stop behavior must remain unchanged.

### Task 2: Return Deterministic Vehicle Resolution and Variant Outcomes

**Files:** `VehicleEntityResolution.java`, `VehicleEntityResolver.java`, `CatalogVehicleResolution.java`, `CatalogVehicleResolver.java`, `CatalogNameIndexService.java`, `CatalogTools.java`, `VehicleTrimCandidate.java`, `CatalogToolsTest.java`, `VehicleEntityResolverTest.java`, new `CatalogVehicleResolverTest.java`, and `ConversationDecisionTest.java`.

**Interfaces:**

```java
public record VehicleTrimCandidate(String model, int year, String trim) { }
public record CatalogVehicleResolution(Status status, VehicleTrimCandidate exact,
                                       List<VehicleTrimCandidate> choices) {
    public enum Status { EXACT, AMBIGUOUS, MISSING, NOT_REQUIRED }
}
```

Keep `VehicleEntityResolution` as the text-extraction result. Add `CatalogVehicleResolver` to compare those mentions to `CatalogNameIndexService.currentEntries()` and emit the separate catalog-resolution output. `CatalogTools.getVehicle` must return explicit exact-match, ambiguous-trim, model-year-unavailable, or variant-unavailable results; no partial trim may be silently promoted to an exact lookup.

- [x] **Step 1: Write failing resolver/catalog tests**
  Cover exact `XLT Gasolina 4x2`, short `XLT` matching both Ranger 2027 XLT variants, unknown trim, and absent model year. Assert ambiguity choices contain canonical trims and ambiguity does not expose vehicle facts as a selected variant.
- [x] **Step 2: Run targeted unit tests and verify expected failure**
  Run `./gradlew test --tests com.ford.service.ai.VehicleEntityResolverTest --tests com.ford.service.ai.CatalogVehicleResolverTest --tests com.ford.service.ai.CatalogToolsTest --tests com.ford.service.ai.ConversationDecisionTest`; new catalog-ambiguity tests must fail because choices/outcomes are not represented.
- [x] **Step 3: Implement catalog-backed candidate matching**
  Resolve `VehicleEntityResolution` against `CatalogNameIndexService.currentEntries()` using normalized exact token boundaries. Store the canonical entry for one match; store every safe canonical candidate for multiple matches. Never turn `VehicleNameIndexEntry` into fact evidence.
- [x] **Step 4: Implement truthful tool outcomes**
  Make `getVehicle` distinguish a model-year miss from a trim miss. For an ambiguous partial trim, return a bounded list of exact trim choices and no selected vehicle. Preserve exact `catalog.find(new VehicleLookup(model, year, canonicalTrim))` for resolved trims.
- [x] **Step 5: Verify unit tests**
  Re-run the four classes. Exact lookups return evidence; ambiguous and missing outcomes cannot pass catalog evidence predicates.

### Task 3: Make Scope, Routing, and Tool Authorization Typed

**Files:** `ScopeDecision.java`, `AuthorizedToolSet.java`, `AssistantScopePolicy.java`, `ToolAuthorizationPolicy.java`, `DefaultAssistantOrchestrator.java`, `DecisionPolicy.java`, `AssistantScopePolicyTest.java`, `ToolAuthorizationPolicyTest.java`, and `AssistantOrchestratorTest.java`.

**Interfaces:**

```java
public record ScopeDecision(Status status, String reasonCode) {
    public enum Status { ALLOWED, DENIED }
}
public record AuthorizedToolSet(Set<String> names) { }
```

- [x] **Step 1: Add failing tests for typed scope and authorization**
  Verify an allowed Ford turn returns `ALLOWED`; denied/out-of-scope returns `DENIED`; authorized tool names are immutable, read-only, and a subset of tools allowed by `DecisionPolicy`.
- [x] **Step 2: Run focused tests and observe missing result types**
  Run `./gradlew test --tests com.ford.service.ai.application.AssistantScopePolicyTest --tests com.ford.service.ai.ToolAuthorizationPolicyTest --tests com.ford.service.ai.AssistantOrchestratorTest`.
- [x] **Step 3: Implement typed scope decision**
  Add `evaluate(...)` returning `ScopeDecision`; retain `reason(...)` as a compatibility adapter that returns the existing reason code. Update `SpringAiAssistantService` to branch on status, never parse reason strings to determine allow/deny.
- [x] **Step 4: Implement immutable authorized tool set**
  Construct `AuthorizedToolSet` only from the intersection of available read-only names and policy-approved names. Pass that same set through the orchestrator and tool context; no later stage may add tools.
- [x] **Step 5: Verify focused tests**
  Re-run the three classes and existing decision-policy tests; denied and unauthorized tools remain unreachable.

### Task 4: Model Tool Execution and Evidence Results

**Files:** `ToolExecutionSnapshot.java`, `ToolResultTracker.java`, `ResponseEvidenceStatus.java`, `ObservedToolCallback.java`, `SpringAiChatCompletionAdapter.java`, and `ObservedToolCallbackTest.java` plus adapter tests.

**Interfaces:**

```java
public record ToolExecutionSnapshot(List<ToolCall> calls) {
    public enum Status { RETURNED, EMPTY, FAILED }
    public record ToolCall(String toolName, Status status, String reasonCode, Integer evidenceCount) { }
}
```

- [x] **Step 1: Add failing tracker tests**
  Assert attempted-but-empty is `EMPTY`, nonempty return is `RETURNED`, exceptions are `FAILED`, and snapshots are immutable. Assert empty/failed tools never enter the evidence-returned set.
- [x] **Step 2: Run targeted tests and observe missing snapshots**
  Run `./gradlew test --tests com.ford.service.ai.ToolResultTrackerTest --tests com.ford.service.ai.ObservedToolCallbackTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`.
- [x] **Step 3: Add immutable execution snapshots**
  Record one safe status per tool attempt, preserving the existing single-attempt rule and current failure disposition. Keep captured tool payloads private to the current generation and do not expose them in telemetry.
- [x] **Step 4: Add safe get_vehicle diagnostics**
  Extend `ObservedToolCallback` to emit only year/trim presence, JSON value type, and match outcome. Do not log any raw argument values. Ensure log tests reject raw model/trim strings.
- [x] **Step 5: Bind evidence status to tool results**
  Add `EvidenceResult(Status status, List<String> sourceReferences, int evidenceCount)` with `Status { SUPPORTED, UNKNOWN, CONFLICTED }`. Derive it from current-turn tool results and source metadata; an empty or failed required tool result maps to `UNKNOWN` and cannot become supported evidence. Return `CONFLICTED` only when the existing evidence payload explicitly contains contradictory values for the same identity/fact; otherwise do not infer conflict.
- [x] **Step 6: Verify unit tests**
  Run the three targeted test classes and `./gradlew test --tests com.ford.service.ai.ReadOnlyToolContextTest`.

### Task 5: Type Answer, Quality, and Final Turn Outcomes

**Files:** `QualityDecision.java`, `EvidenceGroundedResponsePolicy.java`, `AssistantResponse.java`, `AssistantTurn.java`, `SpringAiAssistantService.java`, `SpringAiChatCompletionAdapter.java`, `EvidenceGroundedResponsePolicyTest.java`, `SpringAiAssistantServiceTest.java`, and `SpringAiChatCompletionAdapterTest.java`.

**Interfaces:**

```java
public record QualityDecision(Status status, String reasonCode) {
    public enum Status { PASS, REPAIR, CLARIFY, HANDOFF, BLOCK }
}
public enum ResponseKind { ANSWER, CLARIFICATION, SAFE_FALLBACK }
```

`AssistantResponse` is the typed answer-draft output; `AssistantTurn` remains the finalized public turn output. Do not create duplicate `AnswerDraft` or `TurnResult` wrappers.

- [x] **Step 1: Add failing tests for quality/response statuses**
  Assert supported evidence yields `PASS`; unsupported fact claims yield `REPAIR`; ambiguous trim yields `CLARIFY`; policy-required human involvement yields `HANDOFF`; unrecoverable unsafe output yields `BLOCK` and is never delivered.
- [x] **Step 2: Run focused tests and observe boolean/string-only outcomes**
  Run `./gradlew test --tests com.ford.service.ai.EvidenceGroundedResponsePolicyTest --tests com.ford.service.ai.SpringAiAssistantServiceTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`.
- [x] **Step 3: Implement typed quality decision**
  Replace `Verdict(boolean accepted, String reason)` internally with `QualityDecision`; keep `evaluate` compatibility only if existing callers require it. Map every existing rejection reason to a bounded code and explicit status.
- [x] **Step 4: Add explicit response kind and validate evidence links**
  Add `ResponseKind` to `AssistantResponse`; factual `ANSWER` requires appropriate evidence status, clarification cannot claim a selected trim, and safe fallbacks cannot claim catalog facts. Keep compatibility constructors defaulting to the existing safe behavior.
- [x] **Step 5: Enforce finalization in the service**
  In `SpringAiAssistantService`, only `PASS`, approved `CLARIFICATION`, or policy-authorized `HANDOFF` may form an `AssistantTurn`. `REPAIR` must pass through the bounded repair path; `BLOCK` throws the existing review disposition. Preserve handoff policy unchanged.
- [x] **Step 6: Verify focused tests**
  Re-run the three classes plus `./gradlew test --tests com.ford.service.ai.AssistantResponseQualityPolicyTest` if present; no raw model draft may bypass quality.

### Task 6: Emit Bounded State Traces and Verify Legal Transitions

**Files:** `DecisionTraceEvent.java`, `DecisionTraceCollector.java`, `SpringAiAssistantService.java`, `src/test/java/com/ford/service/conversations/DecisionTraceEventTest.java`, and `SpringAiAssistantServiceTest.java`.

- [x] **Step 1: Add trace vocabulary tests**
  Cover allowed stage names and outcomes for security, classification, entity resolution, scope, authorization, tool, evidence, answer, quality, handoff, and finalization. Reject unknown stage/outcome strings and unbounded values.
- [x] **Step 2: Run trace tests and confirm new stage names fail**
  Ran `./gradlew test --tests com.ford.service.conversations.DecisionTraceEventTest`; expected failure was the missing stage/outcome allowance.
- [x] **Step 3: Add bounded stages and terminal outcomes**
  Extend trace validation with explicit stage/outcome constants. Trace only reason codes, tool names from the allowlist, counts, and timestamps; never text payloads or tool arguments.
- [x] **Step 4: Wire transitions and terminal-state checks**
  Record each stage result. Stop immediately for `BLOCKED`, `DENIED`, `AMBIGUOUS`, required `CLARIFICATION`, or `HANDOFF`; no terminal outcome may flow to factual answer generation. Record explicit skip outcomes for non-applicable evidence stages.
- [x] **Step 5: Verify unit tests**
  Ran `./gradlew test --tests com.ford.service.conversations.DecisionTraceEventTest --tests com.ford.service.ai.SpringAiAssistantServiceTest`; both passed.

### Task 7: Verify Snapshot Coverage and Full Webhook Replay

**Files:** `QwenTwoTurnWebhookReplayIntegrationTest.java`, `LocalCatalogSnapshotLoaderTest.java`, any focused AI integration tests required by the new contracts, and replay artifacts under `build/reports/local-qwen/`.

- [ ] **Step 1: Run all unit tests first**
  Run `./gradlew test` from `ford-ai-service`. All unit tests must pass before integration tests.
- [ ] **Step 2: Run focused integration tests**
  Run catalog snapshot import/parity tests and replay safety tests. Verify all canonical trims are in the cache and exact Ranger 2027 XLT lookups return facts.
- [ ] **Step 3: Run turn 73 live after the validated prefix**
  Use the latest validated transcript through turn 72 as the seed, set `FORD_QWEN_REPLAY_SEED_THROUGH_TURN=72`, and force turns 5 and 73 live with `FORD_QWEN_REPLAY_FORCE_LIVE_TURNS=5,73`. Turn 5 stays live because an existing semantic-catalog persistence assertion requires its real tool evidence. Require exact trim match or clarification at turn 73; never bypass safety checks.
- [ ] **Step 4: Retry the complete diagnostic replay**
  Run the seeded replay with only the latest validated prefix. Record each passing boundary and stop on the first gate failure; do not seed failed turns.
- [ ] **Step 5: Run final acceptance**
  Run a fresh unseeded 100-turn live-Qwen replay. Require 100/100 committed turns, zero handoffs, all safety/quality checks passing, all state outputs valid, and truthful provenance.
- [ ] **Step 6: Review artifacts and workspace changes**
  Confirm transcripts and telemetry contain bounded stage outputs only, raw/prefetched separation remains intact, and unrelated workspace changes remain untouched.
