# Progressive Qwen Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resume local Qwen diagnosis from the last validated turn using recorded prior replies, run live Qwen only over a small target window, and retain a final clean 100-turn run as the release gate.

**Architecture:** The existing integration test uses disposable Testcontainers PostgreSQL, so its database cannot survive a code-edit/retest cycle. A local-only seed adapter will replay validated assistant replies from the prior transcript through the real webhook and memory pipeline, while live Qwen runs only from the requested start turn through end turn. Transcript and telemetry must distinguish seeded turns from live-Qwen turns; failed turns are never accepted as seeds. Safety and no-handoff checks remain active for every committed turn.

**Tech Stack:** Java, Spring Boot integration tests, JUnit 5, AssertJ, Jackson, Testcontainers, local Ollama/Qwen.

## Global Constraints

- Changes stay in `ford-ai-service` test/integration-test replay code until separate approval for production tracing implementation.
- Keep synthetic local data only; do not write production data.
- A seed transcript may supply only committed, previously safety-validated replies; never seed a turn that failed validation.
- Seed mode must be explicit and off by default; final acceptance remains a fresh live 100-turn replay with no seeded replies.
- Do not weaken `LocalReplaySafetyAssertions`, handoff assertions, comparison-evidence requirements, or raw/prefetched separation.
- Comparison evidence remains unknown when missing; absence is not equality and is not proof that the catalog lacks the fact.

---

### Task 1: Read Validated Replies From a Prior Transcript — In Progress

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReader.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReaderTest.java`

**Interfaces:**
- Produces `List<SeedReply>` in ascending turn order.
- `SeedReply` fields: `int turnNumber`, `String buyerMessage`, `String assistantReply`.
- Reader accepts a transcript `Path`, scenario turns, and `seedThroughTurn`; it rejects missing turns, buyer-message mismatches, blank replies, and any requested seed turn after a terminal failure boundary.

- [ ] **Step 1: Write parser tests first**

Cover a transcript with two committed turns plus a committed-but-failed turn; requesting a seed through turn 2 returns only the first two replies. Requesting a seed through the failed turn throws `IllegalArgumentException`. Also reject a buyer-message mismatch and a missing turn.

- [ ] **Step 2: Verify RED**

Run from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptSeedReaderTest
```

Expected: compilation fails because the reader and `SeedReply` do not exist.

- [ ] **Step 3: Implement the strict transcript reader**

Parse only `## Turn N — ...` sections. Capture text between `**Buyer**` and `**Assistant**`, then between `**Assistant**` and the next tools/diagnostics/turn/terminal section. Stop at `## Validation failed...`, `## Replay stopped...`, or `## Complete`. Require exact fixture buyer text and contiguous turn numbers from 1 through `seedThroughTurn`.

- [ ] **Step 4: Verify GREEN**

Run the same test command. Expected: parser tests pass; invalid or failed-turn seeds are rejected.

---

### Task 2: Replay Seed Replies Through the Real Webhook Pipeline — In Progress

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSeededChatCompletionPort.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSeededChatCompletionPortTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`

**Interfaces:**
- `LocalQwenSeededChatCompletionPort(ChatCompletionPort liveDelegate, List<SeedReply> replies, int seedThroughTurn)`.
- For the first `seedThroughTurn` completions, assert the request message matches the next `SeedReply.buyerMessage`, return its assistant reply with provider `recorded-seed`, and make no live model call.
- After the prefix, delegate every completion to the live Qwen port unchanged.
- Keep `ReplayPrefetchingChatCompletionPort` outside this adapter so buyer history, pair cache, prefetch, trace origin, and actual webhooks still run for seed turns.

- [ ] **Step 1: Write the seeded-prefix and live-delegation tests first**

Use a recording delegate. Assert the first two requests return recorded replies, the third reaches the live delegate, message-order mismatch fails closed, and the delegate is untouched during seed turns.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenSeededChatCompletionPortTest
```

Expected: compilation fails because the seeded port does not exist.

- [ ] **Step 3: Implement seeded response playback**

Add an immutable cursor over the validated seed replies. Return:

```java
new AssistantResponse(seed.assistantReply(), false, "recorded-seed", List.of())
```

Use a synchronized cursor and reject extra/mismatched prefix calls. Do not catch and hide mismatch exceptions.

- [ ] **Step 4: Wire the local-only mode**

In the integration-test bean post-processor, read explicit seed path/turn properties. When seed mode is enabled, wrap the existing live delegate with `LocalQwenSeededChatCompletionPort`, then wrap that result with `ReplayPrefetchingChatCompletionPort`. With seed mode absent, preserve current live behavior exactly.

- [ ] **Step 5: Verify GREEN and configuration isolation**

Run the seeded-port tests and `LocalQwenComparisonPrefetchConfigurationTest`. Expected: seed mode is opt-in; ordinary and final full replay use the live delegate.

---

### Task 3: Add Replay Windows and Truthful Seed/Live Reporting

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java`

**Interfaces:**
- Optional env settings: `FORD_QWEN_REPLAY_SEED_TRANSCRIPT` and `FORD_QWEN_REPLAY_SEED_THROUGH_TURN`.
- Seed turns rebuild state through real HTTP webhook requests. Live turns call Qwen. Both paths keep handoff and safety assertions enabled.
- Transcript labels each reply `RECORDED_SEED` or `LIVE_QWEN`; telemetry records the same origin. A seeded 100-turn run is labeled as seeded and does not count as the final all-live acceptance.

- [ ] **Step 1: Test truthful transcript and telemetry labels**

Assert a seeded reply is visibly labeled `RECORDED_SEED`, a model-generated reply is labeled `LIVE_QWEN`, and a seeded run cannot report itself as a clean all-live acceptance.

- [ ] **Step 2: Verify RED**

Run the two focused writer/telemetry test classes. Expected: missing origin/checkpoint APIs or assertions fail.

- [ ] **Step 3: Implement window selection and reporting**

Load all 100 scenario turns. Rebuild turns through the validated seed prefix using the seed adapter; run live Qwen for every later turn until a gate fails or turn 100 completes. Keep sequential committed-turn checks. Apply safety, no-handoff, and evidence checks to every seeded and live committed response; seeded turns use their recorded validated reply but current deterministic safety rules.

Only an unseeded 1–100 run may report `100/100` as final live-Qwen acceptance. A seeded run must report its seeded prefix and cannot be confused with the final gate.

- [ ] **Step 4: Verify GREEN**

Run focused writer/telemetry tests and `git diff --check` on changed files. Expected: origin labels and checkpoint counts are correct.

---

### Task 4: Diagnose and Fix the Turn-30 Prefetch Boundary

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvidence.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPortTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`

**Interfaces:**
- Record bounded comparison diagnostics: safe message fingerprint/length, `isComparisonIntent`, exact pair in current message, pair found in buyer history, remembered pair present/used, selected prefetch status, and evidence origin/count.
- Do not record raw message text in ordinary logs. In the synthetic replay artifact, any payload snapshot remains test-only and explicitly labeled.

- [ ] **Step 1: Add a failing boundary regression**

Run the actual webhook from turns 1–29 using transcript-seeded replies, then process turn 30 live. Assert the current message is recognized as comparison intent, the exact prior buyer pair is used, `compare_trims` is injected, and the committed response passes the existing comparison safety gate. Capture diagnostic fields when the assertion fails.

- [ ] **Step 2: Verify RED and identify the failing boundary**

Run only the seeded 1–30 window. Expected before the fix: reproduce `NOT_A_COMPARISON` or another concrete boundary mismatch with message fingerprint, intent result, history/cache flags, and selected branch available. Do not change safety assertions.

- [ ] **Step 3: Make one root-cause fix**

Fix only the boundary proven by the diagnostic (input transformation, stale/missing pair state, resolver intent, or bean wiring). Do not infer missing catalog facts from missing prefetch.

- [ ] **Step 4: Verify turn 30 GREEN**

Rerun only the seeded prefix plus live turn 30. Expected: `compare_trims` evidence appears for turn 30 and all existing safety/handoff checks pass.

---

### Task 5: Advance in Small Windows, Then Run Final Full Replay

**Files:**
- Test: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Artifacts: `build/reports/local-qwen/100-turn-prefetched-qwen-transcript-*.md`, matching HTML and telemetry JSONL.

- [ ] **Step 1: Resume live Qwen at turn 30**

Use the latest transcript containing validated replies through turn 29 as seed; run Qwen live from turn 30 onward. If a turn fails, seed only through the last passing turn on the next run. Never seed the failed turn, even if its reply committed before validation.

- [ ] **Step 2: Continue from each new failure boundary**

If the run fails at turn 40, seed through turn 39 and run live from turn 40; repeat this rule at each later failure. Keep all safety and no-handoff assertions on every turn.

- [ ] **Step 3: Run the clean final gate**

After all windows pass, run with seed mode disabled:

```bash
FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
./gradlew integrationTest --rerun-tasks --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected: 100 live-Qwen turns committed, zero handoffs, every safety assertion passes, and turn 30 carries positive `compare_trims` replay evidence. The run must not report seeded turns.

## Self-Review

- The plan addresses the observed turn-30 failure without assuming catalog data is absent.
- Seed mode is explicit, test-only, and clearly labeled; the final 100-turn gate remains fully live.
- Safety/handoff gates run for all turns and are never relaxed.
- A failed committed reply is not eligible as seed for the next window.
- The plan intentionally covers replay recovery first; secure production-wide payload storage/model/tool/database tracing remains a separate implementation plan based on the approved diagnostics design.
