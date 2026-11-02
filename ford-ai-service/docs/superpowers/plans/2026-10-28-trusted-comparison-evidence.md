# Trusted Comparison Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute inline; no delegated agents.

**Goal:** Make fresh replay comparison evidence visible to the adapter without fabricating model tool calls or weakening safety.

**Architecture:** An internal, request-bound envelope travels in ToolContext, not in HTTP payloads or trusted prompt markers. ToolResultTracker keeps prefetched payloads separate from model tool execution. The adapter skips omission fallback only when authorized, validated comparison evidence exists and still runs response checks.

**Tech Stack:** Java, Spring AI, Jackson, JUnit 5, Mockito, Gradle.

## Global Constraints

- Use the production webhook and conversation graph. Do not introduce a second graph or agents.
- Keep provenance REPLAY_PREFETCH distinct from MODEL and RECORDED_SEED.
- Production requests without this envelope behave unchanged.
- No deployment, production writes, emme-2 writes, or catalog import is authorized by this feature.
- No concurrent Gradle invocations against shared test outputs.
- Preserve unrelated uncommitted files; commit only new task-owned files or isolated patches.

## Task 1: Internal envelope and evidence ledger

**Files:**
- Create `src/main/java/com/ford/service/ai/application/PrefetchedComparisonEvidence.java`.
- Modify `src/main/java/com/ford/service/ai/application/ToolContext.java`.
- Modify `src/main/java/com/ford/service/ai/application/ToolResultTracker.java`.
- Create `src/test/java/com/ford/service/ai/PrefetchedComparisonEvidenceTest.java`.

**Interfaces:** `PrefetchedComparisonEvidence.liveComparison(AssistantRequest owner, String snapshotSha256, String model, int year, List<String> trims, String comparisonPayload)` validates the live result. `ToolContext.withPrefetchedComparison(PrefetchedComparisonEvidence evidence)` preserves tenant, conversation, tool authorizations, and collector. `ToolResultTracker.acceptPrefetchedComparison(AssistantRequest request, ToolContext context, Set<String> authorizedTools)` returns whether evidence was accepted. `hasPrefetchedEvidence()` identifies this origin without adding attempted tool calls.

- [ ] Write failing tests for nonempty exact comparison, wrong tenant/conversation, same-text new request, snapshot hash, mismatched model/year/trim, missing or duplicate trims, missing provenance, empty differences and prices, expired evidence, text markers, and tracker snapshots remaining empty.

```java
assertThat(tracker.acceptPrefetchedComparison(request, context, Set.of("compare_trims"))).isTrue();
assertThat(tracker.hasEvidence()).isTrue();
assertThat(tracker.hasAttempted()).isFalse();
assertThat(tracker.snapshot().calls()).isEmpty();
assertThat(tracker.returnedToolNames()).isEmpty();
```

- [ ] Run `./gradlew test --tests com.ford.service.ai.PrefetchedComparisonEvidenceTest`; expect missing feature failure before implementation.
- [ ] Implement a private-constructor, content-redacted envelope. Bind to the exact AssistantRequest object and matching context tenant/conversation, record execution UUID/snapshot hash/time, and reject evidence older than five minutes. Parse the comparison result with Jackson; validate exact model/year, two distinct complete trims, an empty missingTrims list, one matching comparison pair, and source-backed prices or differences. Never deserialize this envelope from a controller request. Preserve old ToolContext constructors with a null envelope.
- [ ] Keep prefetched payloads in a separate tracker map. Include them in grounding/source payloads and positive evidence checks, but never in attempted/returned model names or ToolExecutionSnapshot. Require compare_trims in the actual adapter-authorized tools.
- [ ] Run the envelope tests plus `ToolResultTrackerTest` and `ReadOnlyToolContextTest`; expect zero failures.

## Task 2: Adapter omission regression and quality checks

**Files:**
- Modify `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`.
- Create `src/test/java/com/ford/service/ai/PrefetchedComparisonAdapterTest.java`; keep the existing large, user-modified adapter test unchanged.

**Consumes:** Task 1 envelope and tracker interfaces.
**Produces:** A single ordinary model generation can use authorized prefetched evidence; model-origin snapshots remain unchanged.

- [x] Add the turn-27 regression: a mocked model returns a grounded comparison without tools. Pass an envelope through ToolContext. Assert one model call, no forced get_vehicle callback, accepted read-only evidence, and no invented model trace.

```java
var response = adapter.complete(request, context.withPrefetchedComparison(evidence));
assertThat(response.evidenceStatus()).isEqualTo(ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
verify(requestSpec, times(1)).call();
```

- [ ] Run the exact regression before changing the adapter; expect missing-tool fallback/withholding.
- [ ] After constructing the existing authorized callbacks, call tracker acceptance with their names. Change only the omission retry condition:

```java
if (decisionPolicy.requiresVolatileInformation(request.decision())
        && !toolResults.hasAttempted() && !toolResults.hasPrefetchedEvidence()
        && !shouldUseKnowledgeRag(request)) {
    // Existing repair and required-tool fallback stay unchanged.
}
```

- [ ] Add negative tests: context without an envelope and prompt-marker-only requests still withhold; unauthorized/mismatched envelopes cannot skip fallback; unsupported numerical claims still reach the existing grounding/repair policy; model ambiguity still yields clarification.
- [ ] Run `./gradlew test --tests com.ford.service.ai.PrefetchedComparisonEvidenceTest --tests com.ford.service.ai.ToolResultTrackerTest --tests com.ford.service.ai.ReadOnlyToolContextTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`; expect zero failures before integration tests.

## Task 3: Replay wrapper handoff

**Files:**
- Modify `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`.
- Modify `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`.

**Consumes:** Request-bound envelope in ToolContext.
**Produces:** Live comparison prefetch injects both prompt evidence and the separately validated envelope for the exact enhanced request passed to the adapter.

- [ ] Add a delegate-capture regression asserting context evidence is bound to the enhanced request, not its pre-enhancement copy. Assert recorded-seed replies and unresolved/incomplete catalog comparisons carry no envelope. Use a fixed valid SHA-256 in unit fixtures; runtime config obtains the actual catalog manifest SHA-256.
- [ ] Run `./gradlew integrationTest --tests com.ford.service.conversations.ReplayPrefetchingChatCompletionPortTest`; expect the new assertion to fail.
- [ ] Add a snapshot-hash supplier to the wrapper's full constructor; retain existing overloads without trusted-envelope issuance unless a supplier is explicitly supplied. In runtime configuration, lazily load `LocalCatalogSnapshotLoader.manifestSha256(LocalCatalogSnapshotSelection.fromEnvironment(System.getenv()).exportDirectory())`; wrap IO failure as IllegalStateException before passing evidence onward.
- [ ] Once the complete comparison has been validated and size-bounded, create the enhanced request, build its envelope from the unwrapped comparison JSON and exact pair, then pass `context.withPrefetchedComparison(envelope)` to the delegate. Keep REPLAY_PREFETCH trace emission unchanged. Null context must not manufacture authorization or identity.
- [ ] Run wrapper, prefetcher, seeded-adapter, transcript, telemetry, and execution-mode tests serially. Expect no seeded calls or fabricated MODEL traces.

## Task 4: Verification checkpoint

- [ ] Inspect task-only diff and run `git diff --check` on changed task files. Preserve baseline modifications when staging.
- [ ] Run focused live diagnosis around turn 27 only after offline tests pass; use preflight and replay selectors in one serial Gradle invocation. Label checkpoint setup as diagnostic continuity, not live acceptance.
- [ ] Run a fresh all-live 100-turn acceptance only after focused comparison passes. Never count held reviews or seeded turns as catalog acceptance. Do not import catalog data unless acceptance and separate import safeguards pass.

## Subsequent delivery

Advisor approval and mandatory handoff are a separate implementation plan after this independently testable evidence boundary. They must use a dedicated live approval aggregate, existing takeover/handback, atomic outbox sending, optimistic versions, and separate handoff-flow acceptance from the catalog zero-handoff replay. The approved requirements remain in `../specs/2026-10-28-trusted-evidence-advisor-review-design.md`; this plan does not claim to deliver the frontend or approval workflow.

## Execution checkpoint — 2026-10-28

- Envelope, internal context handoff, separate grounding storage, adapter omission regression, and runtime replay wiring are implemented.
- Added two independent regression classes rather than modifying the large pre-existing adapter test file.
- Baseline adapter/tracker/context unit tests passed before changes. New-feature tests failed before implementation; adapter regression reproduced the forced-tool path. Prompt fixture required an advisors mock, corrected before the feature change.
- AI and architecture unit suite: 570 tests, zero failures/errors/skips after the review fix.
- Offline replay/checkpoint integration selection passed after unit verification.
- Code review found partially sourced price fields could ride alongside valid equipment facts. Added a failing mixed-validity regression, then required both source-backed MXN prices for any populated price/delta. Reviewer confirmed the important finding resolved.
- Focused live turns 25–27 launched from a minimal SYNTHETIC_FIXTURE at turn 24. It contains a courtesy exchange, no seeded catalog claims. This is not a reproduction of the entire preceding 24-turn history and is not all-live acceptance.
- Focused result verified: run `d1e03085f59e416eaa17bf6e87b19047`; preflight and live-window JUnit tests both executed (2 tests, zero skipped/failures/errors). Turns 25–27 each made one live provider call and committed; each had live comparison prefetch, no recorded-seed evidence, and zero handoffs. Turn 25 additionally selected compare_trims through the model; turns 26–27 did not select tools and used validated prefetched comparison evidence without forced get_vehicle.
- Response-quality observation: turn 27 presented both transmissions as automatic ten-speed but described transmission as a distinguishing feature. Automated safety/evidence assertions passing does not prove semantic comparison accuracy. Investigate this separately before declaring the focused diagnosis sufficient for fresh full acceptance; do not weaken the catalog acceptance gate.
- Existing manifest helper already wraps IO validation failures; runtime supplier propagates those failures directly instead of adding a nonexistent checked IOException catch.
- Ford-web isolated worktree prepared at `/private/var/folders/9b/xhwrfrls11v7m0b_nxckwlbr0000gn/T/opencode/ford-web-advisor-review`, branch `feat/advisor-message-review`. Frozen Bun install succeeded; baseline 126 Vitest tests and TypeScript check passed. No frontend application code changed yet.
- Remaining: semantic comparison-quality investigation, then fresh full-live acceptance if focused diagnostics pass. Live advisor approval, mandatory routing additions, and web review controls remain unimplemented.

## Comparison-quality follow-up

The shared comparison helper in `CatalogTools` used raw String.equals for fact
values. Thus capitalization, accent spelling, and whitespace differences entered
the tool's differences list as if they were equipment changes. Added a failing
regression using the observed ten-speed transmission wording alongside an actual
four-position versus six-position seat change. The fix normalizes case, diacritics,
and whitespace for value equality only, preserving source text in returned facts.
Unlike vehicle/name normalization, it preserves technical punctuation and numbers;
a separate test keeps `2.0 L` distinct from `20 L`.

AI/architecture unit selection and offline replay/checkpoint integration selection
passed after this change. Live turns 25–27 are rerunning against the same explicitly
synthetic courtesy fixture. Inspect that new response before clearing the quality
observation or launching fresh all-live acceptance.
