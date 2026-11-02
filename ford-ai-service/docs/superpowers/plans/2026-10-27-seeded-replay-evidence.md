# Seeded Replay Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry only previously validated, snapshot-matched catalog tool traces with seeded replies so diagnostic webhook replays retain existing safety gates.

**Architecture:** Extend the local transcript seed reader to parse source snapshot identity and safe tool summaries per committed turn. Validate the source snapshot against the active catalog before configuring seed mode. Make seeded trace metadata available to replay transcript/safety plumbing with explicit `RECORDED_SEED` provenance; do not alter the assertions' required tool names, outcomes, or positive evidence counts.

**Tech Stack:** Java, Spring Boot integration tests, JUnit 5, AssertJ, Jackson.

## Global Constraints

- Seed mode remains explicit, opt-in, and integration-test-only.
- Failed or uncommitted turns cannot be seeded.
- Snapshot mismatch or missing/malformed required trace fails closed.
- Existing safety, no-handoff, raw/prefetched separation, and precision diagnostic behavior stay unchanged.
- Ordinary logs contain no raw buyer messages, tool arguments, personal data, or secrets.
- Seeded replay is diagnostic only; final acceptance requires a fresh unseeded 100-turn run.

---

### Task 1: Parse Validated Tool Traces From Seed Transcripts

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReader.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReaderTest.java`

**Interfaces:**
- `SeedReply` gains immutable `List<LocalQwenTranscriptWriter.ToolTrace> toolEvents`.
- Seed reader returns source trace summaries only for requested, committed turns before the failure boundary.
- Allowlisted parsed fields: tool name, outcome, evidence count, origin. Ignore raw arguments and other text.

- [ ] **Step 1: Add parser tests for model and replay-prefetch trace rows**
  Add a source transcript fixture containing `get_vehicle — returned — 2 evidence items` and `compare_trims — returned — 1 evidence item`; assert correct tool names, `returned` outcome, counts, and origins.
- [ ] **Step 2: Run the parser test and confirm failure**
  Run `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptSeedReaderTest` from `ford-ai-service`; expect compilation/assertion failure because `SeedReply` has no trace field.
- [ ] **Step 3: Test fail-closed parsing**
  Add cases for malformed count, unknown tool name, and malformed tool row; assert invalid evidence cannot become a successful trace.
- [ ] **Step 4: Implement the allowlisted parser**
  Parse only the `**Tools used**` and `**Replay prefetch**` blocks inside each committed turn section. Map only safe tool identifiers from the existing replay tool allowlist. Parse `started` with no evidence count and `returned — N evidence items` with integer `N`; reject malformed rows and negative counts. Set origin to `MODEL` for `Tools used` and `REPLAY_PREFETCH` for `Replay prefetch`.
- [ ] **Step 5: Run seed-reader tests**
  Run `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptSeedReaderTest`; expect all tests pass, including existing failure-boundary and buyer-message checks.

### Task 2: Bind Seed Data to the Active Catalog Snapshot

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReader.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfiguration.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptSeedReaderTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenComparisonPrefetchConfigurationTest.java`

**Interfaces:**
- Parsed seed metadata includes the transcript's manifest SHA-256.
- Seed configuration compares that SHA-256 with the active `LocalCatalogSnapshotLoader.SnapshotSummary.sha256()` before wrapping the live delegate.

- [ ] **Step 1: Add snapshot metadata mismatch tests**
  Test missing, malformed, and differing source hashes; each must fail before any seeded reply can be used.
- [ ] **Step 2: Run focused tests and confirm failure**
  Run both classes with `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptSeedReaderTest --tests com.ford.service.conversations.LocalQwenComparisonPrefetchConfigurationTest`; expect missing metadata API/assertion failures.
- [ ] **Step 3: Parse and validate source hash**
  Require exactly one `Snapshot manifest SHA-256: ` header containing 64 hexadecimal characters. Compare case-insensitively to active snapshot hash. Throw a bounded configuration exception on mismatch; do not log either hash beyond existing artifact metadata.
- [ ] **Step 4: Re-run focused tests**
  Run the same two classes; expect pass and no Qwen call on invalid seed configuration.

### Task 3: Propagate Recorded Trace Provenance Through Replay

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSeededChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/ReplayPrefetchingChatCompletionPort.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSeededChatCompletionPortTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`

**Interfaces:**
- Seed adapter exposes traces for the exact seeded buyer message / turn without changing `AssistantResponse` production API.
- `ToolTraceOrigin` gains `RECORDED_SEED`; telemetry origin remains `ReplyOrigin.RECORDED_SEED`.

- [ ] **Step 1: Write failing seeded trace propagation tests**
  Verify exact seeded turn returns its parsed traces; wrong message/turn returns no trace and cannot borrow neighboring evidence. Verify writer and telemetry emit `RECORDED_SEED`.
- [ ] **Step 2: Run focused tests and confirm failure**
  Run the seeded adapter, transcript writer, telemetry, and replay-safety test classes; expect missing propagation/origin behavior.
- [ ] **Step 3: Implement immutable seed trace lookup**
  Add exact turn/message trace access to the seeded adapter. Keep the existing request matching and forced-live behavior; live turns expose no seed traces.
- [ ] **Step 4: Attach seed traces to the current webhook turn**
  In the test replay wrapper, attach only the matched seed trace list and relabel its origin `RECORDED_SEED`. Do not synthesize traces when source rows were absent. Preserve current fresh replay-prefetch traces as `REPLAY_PREFETCH`.
- [ ] **Step 5: Keep safety assertions semantically unchanged**
  Permit `RECORDED_SEED` traces only when the current reply origin is `RECORDED_SEED`; retain exact existing required tool-name, `returned`, and positive-count predicates. Keep all handoff checks unchanged.
- [ ] **Step 6: Verify focused tests**
  Run the four focused test classes; expect seeded trace provenance and existing safety predicates to pass.

### Task 4: Verify Seeded Replay Boundary, Then Resume Qwen

**Files:**
- Modify if needed: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Artifacts: `build/reports/local-qwen/100-turn-prefetched-qwen-transcript-*.md` and matching HTML/telemetry.

- [ ] **Step 1: Run cheap tests first**
  Run `./gradlew test --tests com.ford.service.conversations.ProviderRoutedConversationMemoryCompactorTest --tests com.ford.service.ai.ConversationDecisionServiceTest` and all focused integration-test classes from Tasks 1–3. Expect pass before Qwen.
- [ ] **Step 2: Run a seeded webhook window through turn 36**
  Use the validated source transcript and matching oct25 snapshot. Expect seed tool traces preserve turn 36's successful `get_vehicle` evidence and keep safety/no-handoff checks active.
- [ ] **Step 3: Resume live Qwen at turn 59**
  Seed only through 58, run the webhook replay with turn 59 live, and capture safe component-stage diagnostics if it fails. Do not relax gates.
- [ ] **Step 4: Advance from each passing boundary**
  Use only the latest validated prefix for each retry. Never seed a failed turn.
- [ ] **Step 5: Run final acceptance only after seeded diagnosis completes**
  Run a fresh unseeded 1–100 live-Qwen replay. Require 100/100, zero handoffs, all safety checks passing, and truthful transcript/telemetry provenance.
