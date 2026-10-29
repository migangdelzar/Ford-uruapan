# Qwen Turn-31 Diagnostic Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add an explicit, test-only live replay window through at most turn 32 and record enough sanitized failure data to diagnose the historical review stop.

**Architecture:** Extend `LocalQwenReplayExecutionMode` with a bounded diagnostic lane and validate its end-turn setting before any webhook is sent. A small replay-window runner will process the natural scenario prefix sequentially. The existing webhook/Testcontainers harness remains the integration boundary; failure records add only allowlisted category and persisted turn-state fields, never exception messages or secrets.

**Tech Stack:** Java 25, Spring Boot integration-test source set, Gradle, JUnit 5, AssertJ, PostgreSQL Testcontainers, local Ollama/Qwen.

## Global Constraints

- Do not run the fresh 100-turn acceptance until the historical failure is explained and the focused replay passes.
- Do not write to production or `emme-2`; do not import catalog data.
- Do not seed turns from the old unverified transcript.
- Use only the approved local snapshot and disposable PostgreSQL for live replay.
- Keep existing no-handoff, response-safety, evidence, retry, and tool-authorization behavior unchanged.
- Missing snapshot/BGE preflight must skip replay before any webhook is sent.
- Diagnostic end turn must be an integer from 1 through 32 inclusive.
- Diagnostic transcript must say it is diagnostic, not final all-live acceptance.
- Failure output may include safe category, exception class, persisted turn state/failure category/review reason, provider/model and call counts, tool names/outcomes/evidence counts, and memory-shape counts; never include raw exception text, credentials, or production data.

---

### Task 1: Add fail-closed bounded diagnostic configuration

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionMode.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionModeTest.java`

**Interfaces:**
- Add enum value `BOUNDED_LIVE_DIAGNOSTIC`.
- Add `static int boundedDiagnosticEndTurn(Map<String, String> environment)`; require `FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC`, parse `FORD_QWEN_DIAGNOSTIC_END_TURN`, and accept only 1–32.
- Bounded mode rejects seed settings, checkpoint settings, and `FORD_QWEN_100_REPLAY_ENABLED=true`.

- [x] **Step 1: Add failing mode tests**

Test default resolution remains `LIVE_DIAGNOSTIC`; bounded mode with end turn `32` resolves and returns 32; missing, zero, negative, nonnumeric, and 33 are rejected; mode without explicit bounded mode is rejected by the end-turn accessor; seed/checkpoint/100-turn settings are rejected in bounded mode.

```java
assertThat(LocalQwenReplayExecutionMode.resolve(Map.of(
        "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC",
        "FORD_QWEN_DIAGNOSTIC_END_TURN", "32")))
        .isEqualTo(LocalQwenReplayExecutionMode.BOUNDED_LIVE_DIAGNOSTIC);
assertThat(LocalQwenReplayExecutionMode.boundedDiagnosticEndTurn(Map.of(
        "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC",
        "FORD_QWEN_DIAGNOSTIC_END_TURN", "32"))).isEqualTo(32);
```

- [x] **Step 2: Run focused integration-test classes and observe RED**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest
```

Expected: compile/test failure because the bounded mode and end-turn accessor are absent.

- [x] **Step 3: Implement configuration validation**

Parse with the existing positive-integer validation pattern. Require explicit mode, reject values above 32, and reject existing seed/checkpoint/100-turn flags. Leave all existing mode resolution behavior unchanged when bounded mode is absent.

- [x] **Step 4: Rerun focused integration-test class**

Run the same Gradle command. Expected: all `LocalQwenReplayExecutionModeTest` cases pass.

- [x] **Step 5: Commit the configuration boundary**

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionMode.java src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionModeTest.java
git commit -m "test: add bounded Qwen replay mode"
```

---

### Task 2: Add a sequential replay-window runner

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayWindow.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayWindowTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenFailureCategoryTest.java`

**Interfaces:**
- `LocalQwenReplayWindow.run(List<NaturalBuyerConversationScenario.Turn> turns, int endTurn, TurnHandler handler)` processes turns 1 through `endTurn` in order.
- `TurnHandler.process(Turn turn)` may throw `Exception`; the first exception stops the window and propagates unchanged.

- [x] **Step 1: Write runner tests first**

Use a 4-turn synthetic list and recording handler. Verify end turn 3 processes exactly turns 1, 2, 3 in order. Verify handler failure on turn 2 propagates and turn 3 is never processed. Verify end turns 0 and 33 fail before handler invocation.

```java
var seen = new ArrayList<Integer>();
LocalQwenReplayWindow.run(turns, 3, turn -> seen.add(turn.number()));
assertThat(seen).containsExactly(1, 2, 3);
```

- [x] **Step 2: Run focused integration-test class and observe RED**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest
```

Expected: compile failure because the window runner does not exist. This test has no Spring context or container dependency.

- [x] **Step 3: Implement the bounded sequential runner**

Validate `turns` and `handler`, require `1 <= endTurn <= min(32, turns.size())`, verify scenario turn numbers are contiguous starting at 1, and invoke the handler for each prefix turn. Do not catch handler exceptions.

- [x] **Step 4: Rerun runner tests**

Run the same `integrationTest --tests` command. Expected: all ordering, bound, and stop-on-failure tests pass.

- [x] **Step 5: Commit the runner boundary**

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayWindow.java src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayWindowTest.java
git commit -m "test: add bounded replay window runner"
```

---

### Task 3: Persist allowlisted failure diagnostics

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenFailureDiagnostics.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRendererTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java`

**Interfaces:**
- `LocalQwenFailureDiagnostics` holds `FailureCategory safeReason`, safe exception class name, and nullable allowlisted persisted state, failure category, and review reason.
- Extend `LocalQwenTranscriptWriter.recordFailure(...)` and `LocalQwenTurnTelemetry.TurnRecord` to accept optional diagnostics; existing callers default to no diagnostic detail.
- Never serialize `Throwable.getMessage()`, stack trace, tool arguments, or raw provider error.

- [x] **Step 1: Add failure-output tests first**

Test Markdown and HTML output includes the safe reason and safe diagnostic fields, and excludes a synthetic secret/message sentinel. Test JSONL includes the allowlisted fields and excludes the same sentinel. Test invalid field formats are rejected.

```java
assertThat(markdown).contains("SAFE_FALLBACK_REVIEW", "assistant_response_review")
        .doesNotContain("SYNTHETIC_SECRET_SENTINEL", "raw provider error");
```

- [x] **Step 2: Run focused integration-test classes and observe RED**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenTranscriptHtmlRendererTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest
```

Expected: compilation failure for the diagnostics value/API.

- [x] **Step 3: Capture structured failure state at the source**

In `awaitCommittedReply`, keep the existing query of `state`, `failure_category`, and `review_reason`. For invalid turn state, throw a typed test exception carrying those fields rather than recovering them from a message string. For timeout and other failures, record only the known `FailureCategory` and exception simple class name. Use a strict identifier allowlist such as `[A-Za-z0-9_]{1,64}` for persisted categories and review codes.

- [x] **Step 4: Write diagnostic fields to transcript and telemetry**

Pass the typed diagnostics through the existing replay failure catch. Add them to Markdown, HTML `FailedTurn`, and the failed `TurnRecord`; preserve current safe failure category and all existing output fields. Retain current behavior for unrelated replay callers by using the overload without diagnostics.

- [x] **Step 5: Rerun focused output tests**

Run the same focused integration-test command. Expected: all tests pass; output contains no sentinel or raw exception content.

- [x] **Step 6: Commit diagnostic output**

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenFailureDiagnostics.java src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRendererTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java
git commit -m "test: capture sanitized replay failure diagnostics"
```

---

### Task 4: Wire and verify the bounded live diagnostic lane

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionModeTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayWindowTest.java`

**Interfaces:**
- Add an integration test gated only by `FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC`.
- Read `FORD_QWEN_DIAGNOSTIC_END_TURN` through `boundedDiagnosticEndTurn`; process the prefix with `LocalQwenReplayWindow` and the existing `postSyntheticMessage`, reply, evidence, safety, no-handoff, and telemetry pipeline.
- Successful bounded transcript ends with `Diagnostic window complete — not all-live acceptance`; it must not use `completeFreshFullLive()`.

- [x] **Step 1: Add failing bounded-transcript completion test**

Create a 2-turn writer with `BOUNDED_LIVE_DIAGNOSTIC`; complete it and assert diagnostic-only wording and execution mode in Markdown and HTML. Assert it does not contain `Fresh all-live acceptance passed` or `2/2 live Qwen turns` acceptance wording.

- [x] **Step 2: Run focused tests and observe RED**

Run the focused integration-test classes:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest
```

Expected: runner tests remain green; the new transcript assertion fails until diagnostic completion wording is added. `LocalQwenReplayExecutionModeTest` is also in the integration-test source set and runs in Task 6.

- [x] **Step 3: Wire opt-in webhook replay**

Add a JUnit test gated on the explicit bounded mode. Require `PREFLIGHT_PASSED` before inserting the synthetic binding or posting a message. Call `runNaturalBuyerReplay(endTurn, "bounded-diagnostic")` through `LocalQwenReplayWindow`; retain the existing empty delivery-bean assertions, per-turn no-handoff assertion, and `LocalReplaySafetyAssertions`. Do not enable or call either 100-turn test.

- [x] **Step 4: Make successful report explicitly diagnostic**

In `LocalQwenTranscriptWriter.complete`, handle `BOUNDED_LIVE_DIAGNOSTIC` before the generic live-complete branch. Write exact count and diagnostic-only wording; do not set `freshAcceptanceValidated` or call `completeFreshFullLive()`.

- [x] **Step 5: Run all unit tests**

Run from `ford-ai-service`:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`; no integration/live test is enabled by this command.

- [x] **Step 6: Run focused non-live integration gates**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenFailureCategoryTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenTranscriptHtmlRendererTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest
```

Expected: `BUILD SUCCESSFUL`; these classes do not send a webhook or call Qwen.

- [x] **Step 7: Run bounded local-Qwen diagnostic only after gates pass**

Use a clean environment. Set the approved local snapshot, local Ollama endpoint/model, and explicit bounded mode. Enable the integration-test class and comparison-prefetch lane, but leave all seed/checkpoint settings, `FORD_QWEN_100_REPLAY_ENABLED`, and all remote-provider credentials unset. Run only the named bounded diagnostic test:

```bash
env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
  FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
  FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
  FORD_AI_CLOUD_ENABLED=false \
  FORD_AI_LOCAL_ENABLED=true \
  FORD_AI_LOCAL_PROVIDER=ollama \
  FORD_AI_LOCAL_BASE_URL=http://localhost:11434 \
  FORD_AI_LOCAL_MODEL_VERSION=qwen3.5:4b \
  FORD_QWEN_REPLAY_MODEL=qwen3.5:4b \
  FORD_QWEN_REPLAY_ENABLED=true \
  FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
  FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC \
  FORD_QWEN_DIAGNOSTIC_END_TURN=32 \
  ./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

The method-filtered invocation skipped because it omitted the ordered snapshot/BGE preflight. Running the test class ensured preflight happened first. Preflight passed. Bounded replay committed turns 1–30, then stopped at turn 31; no handoff occurred and no turn 32 was sent. The test task reports FAILED because the harness deliberately surfaces the recorded replay stop. The transcript and telemetry remain available at:

```text
build/reports/local-qwen/32-turn-prefetched-qwen-transcript-1f8ba9642fdc4d1997aa4c1058331ca3.md
build/reports/local-qwen/turn-telemetry-1f8ba9642fdc4d1997aa4c1058331ca3-oct25-candidate-bounded-diagnostic.jsonl
build/reports/local-qwen/32-turn-transcript-1f8ba9642fdc4d1997aa4c1058331ca3.html
```

Finding: on turn 31, intent routing required vehicle details. Qwen omitted the required tool twice. The forced `get_vehicle` call had a model, no year, and a three-character trim; it returned no catalog evidence. The adapter withheld the answer with `READ_ONLY_TOOL_RETURNED_EMPTY`, raised `AssistantTurnUnavailableException`, and the processor correctly committed `SAFE_FALLBACK_REVIEW` with `assistant_response_review`. This reproduces the review stop, not the old transcript's unsupported price reply. The Gradle XML contains sanitized provider/tool lifecycle logs; the transcript does not expose a raw answer or exception message. No 100-turn acceptance ran.

- [x] **Step 8: Commit the bounded replay integration**

```bash
git add src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenFailureCategoryTest.java docs/superpowers/plans/2026-10-28-qwen-turn-31-diagnostic-replay.md
git commit -m "test: add bounded Qwen diagnostic replay"
```

## Self-Review

- Spec coverage: explicit end-turn opt-in and 1–32 cap are Task 1; sequential local replay and stop-on-failure are Task 2; safe category/state/provider/tool/memory diagnostics and redaction are Task 3; synthetic DB, local snapshot, safety/no-handoff gates, diagnostic-only label, and held 100-turn/import gates are Task 4.
- No new production code, remote provider, catalog write, or transcript seeding is planned.
- Existing `LIVE_DIAGNOSTIC`, `SEEDED_DIAGNOSTIC`, `CHECKPOINT_LIVE_WINDOW`, and `FRESH_FULL_LIVE` behavior stays unchanged.
- The first diagnostic artifact used `INVALID_STATE` for `safeReason` despite preserving `turnState=SAFE_FALLBACK_REVIEW`; failure classification now maps `SAFE_FALLBACK_REVIEW` and `MISSING_CATALOG_EVIDENCE` explicitly, with a focused regression test.
- Bounded live run was local-only, snapshot-backed, and isolated to disposable PostgreSQL. The 100-turn acceptance and catalog import remain paused.
