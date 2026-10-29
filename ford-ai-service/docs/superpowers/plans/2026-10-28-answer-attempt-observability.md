# Answer Attempt Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure every generated answer attempt and log retries with grounded evidence and quality outcomes, without changing answer behavior or retry policy.

**Architecture:** Keep the existing production conversation graph and its existing bounded provider/tool/quality retries. Emit one bounded generation-attempt event for each parsed model output, including confidence band, evidence status, and bounded attempt stage/ordinal; existing assessment events continue to report accepted/withheld quality. Add content-free structured logs with conversation correlation and retry ordinal; never add prompt or answer text to telemetry.

**Tech Stack:** Java 21, Spring Boot, Micrometer Observation, SLF4J, JUnit 5, Mockito, AssertJ, Gradle.

## Global Constraints

- Do not add ReAct, Plan-and-Execute, another graph, or multi-agent routing.
- Do not add answer retries or change current retry limits/triggers.
- Model self-confidence is telemetry only; it must not replace trusted evidence or safety policy.
- Metric labels must remain bounded; never tag metrics with conversation, tenant, message, or output IDs.
- Logs and telemetry must never include prompts, generated answer text, retrieved payloads, or personal data.
- Preserve existing critical handoff, advisor review, fallback, and automatic-reply behavior.

---

## File Map

- `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java` — record initial, required-tool retry, quality-repair, and final-generation retry output events individually; include attempt stage and ordinal in safe logs.
- `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java` — allowlist and bound the new attempt-stage tag.
- `ford-ai-service/src/main/java/com/ford/service/observability/AiProviderCallObserver.java` — only change if a bounded attempt-stage field is required by existing provider-call measurements; do not add an unbounded identifier.
- `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java` — verify one metric/log per answer attempt, including rejected first output and accepted/rejected repair.
- `ford-ai-service/src/test/java/com/ford/service/observability/AiObservationServiceTest.java` — verify stage tag allowlist and bounded values.

### Task 1: Emit and Verify Per-Attempt Answer Assessments — IN PROGRESS

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/observability/AiObservationServiceTest.java`

**Interfaces:**
- Consumes: existing `AiObservationService.event`, `ResponseEvidenceStatus`, model confidence, and quality rejection reasons.
- Produces: bounded `ford.ai.answer.attempt` events with `attempt_stage` values `initial`, `required_tool_retry`, `quality_repair`, or `final_generation_retry`; each parsed output records `confidence_band`, `evidence_status`, and `quality_outcome=pending` until existing final assessment reports accepted/withheld. Content-free logs include conversation ID, attempt ordinal/stage, evidence status, confidence band, and retry reason where applicable.

- [x] **Step 1: Add a failing test for rejected output followed by accepted repair**

Use a recording `AiObservationService` in `SpringAiChatCompletionAdapterTest`. Configure two model outputs: first fails the existing response validator; second passes. Assert two generation-attempt events with `quality_outcome=pending`; stages are `initial` and `quality_repair`; confidence bands and attempt ordinals match each output. Assert logs contain stage/ordinal and do not contain either answer body.

- [x] **Step 2: Run the focused test and confirm it fails**

Run: `./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`
Expected before implementation: no `ford.ai.answer.attempt` events exist.

- [x] **Step 3: Add bounded attempt-stage telemetry and logs**

Pass an explicit stage and 1-based ordinal to each model call. Record a generation-attempt event after each parsed output, with grounded evidence status and the model-confidence band; final answer assessments continue to report accepted/withheld. Do not change attempt count, retry triggers, or route decisions. Add `attempt_stage` and `attempt` to the observation key allowlist and bound them to the four stage names and ordinals 1–4; unknown values become `other`. Do not add identifiers or answer content to metric tags.

- [x] **Step 4: Add tests for quality repair and bounded tags**

Verify quality repair produces one generation-attempt event per parsed output, and `AiObservationServiceTest` bounds unknown stage and ordinal values to `other`. Existing adapter tests continue covering required-tool retry, final-generation retry, rejection, and fallback paths; all use the same instrumented call method.

- [x] **Step 5: Run focused unit tests**

Run: `./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest --tests com.ford.service.observability.AiObservationServiceTest --tests com.ford.service.ai.ProviderRoutingChatCompletionPortTest`
Expected: `BUILD SUCCESSFUL`; retry limits and provider failure behavior remain unchanged.

- [ ] **Step 6: Run full unit suite and inspect the focused diff**

Run: `./gradlew test` from `ford-ai-service`, then `git diff --check -- <the four listed source/test files>`.
Expected: tests pass; no whitespace issues; only intended per-attempt observability behavior changes.

**Verification outcome:** Focused adapter, observation, and provider-routing unit tests pass. Full `./gradlew test` reports 1 failure in `DefaultConversationTurnProcessorTest.explicitHumanRequestUsesExistingHandoffPathBeforeAssistantRuns` (NullPointerException at line 94), outside this observability change. `git diff --check` also reports CRLF trailing-whitespace noise across already-modified test/observability files; those broad pre-existing edits were not normalized.
