# Qwen Replay Harness Implementation Plan

> **For agentic workers:** execute in order; keep every Qwen live run opt-in and isolated.

**Goal:** Add safe, explicit replay modes for checkpointed live diagnosis and fresh all-live acceptance, while retaining seeded replay only for plumbing diagnostics.

**Architecture:** Keep the production webhook and conversation graph unchanged. Add a versioned synthetic checkpoint fixture and restore it into the disposable integration-test database. The checkpoint runner submits only target turns to live Qwen. Fresh acceptance rejects any configured seed and requires 100 live replies. Seeded replay keeps provenance distinct from actual tool evidence.

**Tech Stack:** Java, Spring Boot integration tests, JUnit 5, PostgreSQL Testcontainers, existing local-Qwen transcript/telemetry writers.

## Task 1: Make execution mode and provenance explicit

**Files:**
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java`
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java`
- Modify corresponding writer, HTML, and telemetry tests.

- [ ] Add modes `SEEDED_DIAGNOSTIC`, `CHECKPOINT_LIVE_WINDOW`, and `FRESH_FULL_LIVE`; keep existing raw/prefetched labels compatible where reports depend on them.
- [ ] Add mode, checkpoint/fixture ID, and scenario hash to run metadata; reject seeded origins in full-live completion.
- [ ] Preserve tool-trace origin; do not include recorded seed traces in assertions or database evidence.
- [ ] Add tests proving seeded reports cannot claim all-live acceptance and live/seed counts match turn origins.

## Task 2: Define and validate checkpoint fixture

**Files:**
- Add `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayCheckpoint.java`
- Add a versioned synthetic fixture under `src/integrationTest/resources/local-qwen/checkpoints/`.
- Add `LocalQwenReplayCheckpointTest.java`.

- [ ] Model schema version, scenario ID/hash, catalog label/hash, checkpoint turn, synthetic conversation identity/version, recent messages, summary, durable facts, and source/origin.
- [ ] Validate contiguous turn position, message roles/content, IDs, hash formats, expected catalog snapshot, and schema before any webhook call.
- [ ] Add deterministic scenario hashing over canonical turn number/message/expectation content.
- [ ] Reject missing, malformed, incompatible, or non-synthetic checkpoint data.

## Task 3: Restore checkpoint in disposable PostgreSQL

**Files:**
- Add `LocalQwenReplayCheckpointLoader.java`.
- Modify `QwenTwoTurnWebhookReplayIntegrationTest.java` or add a focused checkpoint integration test.
- Reuse current schema and repository ownership constraints; do not add production migration or production endpoint.

- [ ] Insert synthetic conversation identity and state with tenant/channel/account/participant bindings.
- [ ] Restore ordered inbound/outbound messages and memory summary/facts/version, preserving the memory context epoch and timestamps required by current queries.
- [ ] Verify restored state through the existing memory repository before sending target input.
- [ ] Ensure fixture restoration cannot touch external/production data sources.

## Task 4: Add checkpointed live-window mode

**Files:**
- Modify `QwenTwoTurnWebhookReplayIntegrationTest.java`.
- Modify `LocalQwenComparisonPrefetchConfiguration.java` only if mode validation belongs at bean setup.
- Add tests for mode parsing/validation.

- [ ] Select checkpoint and target turn range through explicit test properties/environment variables.
- [ ] Restore checkpoint, submit target buyer turns through the real webhook, and use the real `ChatCompletionPort` without `LocalQwenSeededChatCompletionPort`.
- [ ] Prove live provider measurement and live catalog tool/evidence events for targets where required.
- [ ] Assert commit order, same conversation, no handoffs, ambiguity safety, and per-turn safety.
- [ ] Write distinct transcript/telemetry artifacts containing fixture ID, hashes, live count, and handoff count.

## Task 5: Harden fresh all-live acceptance

**Files:**
- Modify `QwenTwoTurnWebhookReplayIntegrationTest.java`.
- Modify test task/configuration in `build.gradle` only if needed for unique task names and isolated result paths.
- Add integration assertions.

- [ ] Add explicit `FRESH_FULL_LIVE` mode; fail before preflight/webhook if any seed transcript, seed prefix, or forced-live list is supplied.
- [ ] Require empty conversation state and no seeded adapter; reject non-live reply origins.
- [ ] Require exactly 100 ordered commits, 100 live Qwen turns, zero seeded replies, and zero handoffs.
- [ ] Keep this opt-in; ensure it uses a disposable database and a unique run/artifact ID.

## Task 6: Keep seeded replay diagnostic-only

**Files:**
- Modify `LocalQwenSeededChatCompletionPort.java` as needed.
- Modify `QwenTwoTurnWebhookReplayIntegrationTest.java` and seed/transcript tests.

- [ ] Name and report seeded mode as `SEEDED_DIAGNOSTIC`, never acceptance.
- [ ] Verify saved replies cause no live model call and recorded tool metadata never creates tool events.
- [ ] Apply live catalog assertions only to live turns; seeded turns validate webhook, memory, commit, and safety plumbing only.
- [ ] Keep message mismatch fail-closed; no silent fallback to live Qwen.

## Task 7: Verify and document commands

**Files:**
- Modify replay harness documentation near `docs/superpowers/specs/2026-10-27-qwen-replay-harness-architecture-design.md` or add a focused README.

- [ ] Run targeted unit tests for checkpoint validation, origin accounting, and seed behavior.
- [ ] Run targeted integration tests for fixture restoration and mode gates.
- [ ] Run the focused live window only after unit/integration tests pass; use targets 86–87 and verify actual telemetry/tool events.
- [ ] Run clean 100-turn all-live acceptance only after live-window success.
- [ ] Do not import catalog snapshot as part of this plan; import remains a separate last step after acceptance.

## Implementation notes

- Use JUnit test properties/environment guards so ordinary `./gradlew test` stays fast and offline.
- Never run live replay tasks concurrently against shared Gradle result directories.
- Do not edit unrelated pre-existing worktree changes.
- Do not weaken factual evidence or ambiguity gates to make replay pass.
