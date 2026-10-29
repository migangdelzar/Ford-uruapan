# Synthetic Operator Approval Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow an opt-in bounded local replay to continue only after a synthetic test operator approves one currently approvable held draft through the real message-approval service.

**Architecture:** Keep default `NEEDS_OPERATOR` stop behavior. Add a fail-closed bounded-mode opt-in and a small integration-test coordinator that verifies the pending approval belongs to the exact inbound receipt, then invokes `MessageApprovalService.APPROVE` with the exact stored draft and a synthetic tenant-scoped test operator. Record approved drafts as a distinct diagnostic outcome, never as a committed Qwen reply; rely on the bounded harness's no-delivery bean assertions to prevent external delivery.

**Tech Stack:** Java 25, Spring Boot, Spring AI, JUnit 5, AssertJ, Gradle, PostgreSQL Testcontainers, local Ollama.

## Global Constraints

- Only disposable local replay; no production or `emme-2` writes.
- Synthetic approval flag must be false by default and valid only with `BOUNDED_LIVE_DIAGNOSTIC` capped at 32 turns.
- Use the existing `MessageApprovalService`; do not mark turn state `REPLY_COMMITTED` or bypass freshness, permission, evidence, or idempotency checks.
- Approve only one exact pending draft whose domain action set explicitly contains `APPROVE`; otherwise stop.
- Outbox publisher, outbox delivery router, and channel delivery port must be absent before replay traffic.
- Never use `EDIT`, `REJECT`, `TAKEOVER`, review-item resolution, or a test DB state mutation to simulate approval.
- Transcript and telemetry must distinguish synthetic approval from live assistant commit; report approval counts separately.
- Keep safety checks enabled. Synthetic approval is diagnostic-only, not proof of customer delivery, zero handoffs, historical-failure resolution, or production readiness.
- Do not enable or execute the 100-turn lane.

---

### Task 1: Add explicit bounded-only synthetic approval configuration

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionMode.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionModeTest.java`

**Interfaces:**
- Add `static boolean syntheticOperatorApprovalEnabled(Map<String, String> environment)`.
- Absent/`false` returns false. `true` requires explicit `FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC` and a valid `FORD_QWEN_DIAGNOSTIC_END_TURN` from 1 through 32. Reject other values and all other modes.
- Existing replay mode behavior remains unchanged when the flag is absent or false.

- [ ] **Step 1: Write configuration tests first**

Add tests proving the flag defaults to false, true is accepted only with bounded mode and end turn 32, and true is rejected in `LIVE_DIAGNOSTIC`, `SEEDED_DIAGNOSTIC`, `CHECKPOINT_LIVE_WINDOW`, and `FRESH_FULL_LIVE`. Reject invalid boolean strings and missing/invalid end-turn values. Preserve existing bounded-mode seed/checkpoint/100-turn rejection tests.

- [ ] **Step 2: Run the focused mode test and observe RED**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest
```

Expected: compile failure because `syntheticOperatorApprovalEnabled` is not defined.

- [ ] **Step 3: Implement fail-closed mode validation**

Parse only absent/`false`/`true` (case-insensitive). For `true`, directly require the configured mode string to equal `BOUNDED_LIVE_DIAGNOSTIC` and call `boundedDiagnosticEndTurn(environment)` before returning true; do not call `resolve` from this helper. In `resolve`, invoke the helper once after selecting/validating the mode so invalid settings fail before a webhook can be posted.

- [ ] **Step 4: Rerun mode tests**

Rerun the focused command. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit config slice**

Run `git diff --check`, inspect the two-file diff, then commit:

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionMode.java src/integrationTest/java/com/ford/service/conversations/LocalQwenReplayExecutionModeTest.java
git commit -m "test: gate synthetic approval to bounded replay"
```

---

### Task 2: Build fail-closed synthetic approval coordinator

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApproval.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApprovalTest.java`
- Reference: `src/main/java/com/ford/service/conversations/application/MessageApprovalService.java`
- Reference: `src/main/java/com/ford/service/conversations/application/port/out/MessageApprovalRepository.java`
- Reference: `src/main/java/com/ford/service/conversations/domain/MessageApproval.java`

**Interfaces:**
- `Result approveIfEligible(UUID tenantId, UUID conversationId, UUID sourceReceiptId, String runId)`.
- `Result` contains an allowlisted `Status` and nullable `ApprovedDraft`; statuses: `APPROVED`, `NO_PENDING_APPROVAL`, `MULTIPLE_PENDING_APPROVALS`, `NOT_APPROVABLE`, `APPROVAL_NOT_QUEUED`, `APPROVAL_FAILED`.
- `ApprovedDraft` contains exact approved `draftText`, `approvalId`, and queued `outboundMessageId`. It contains no raw tool payload or exception message.
- Coordinator consumes the actual `MessageApprovalRepository` and `MessageApprovalService`; tests mock these boundaries, replay wiring injects real application beans.

- [ ] **Step 1: Write coordinator tests first**

Use mocks. Verify no/multiple pending approvals return their safe status without calling `MessageApprovalService.act`. Verify nonmatching `sourceReceiptId`, non-`PENDING` status, `draftMayBeApproved=false`, missing `APPROVE` action, null/blank draft all fail closed. For one eligible approval, verify one `act` call with the same tenant/conversation/approval IDs, expected conversation/approval versions, `Action.APPROVE`, exact stored draft, and run-scoped idempotency key. Return `APPROVED` only for a `QUEUED` result with outbound message ID. Map stale/invalid/failed calls to `APPROVAL_FAILED` without exposing exception details.

- [ ] **Step 2: Run the focused coordinator test and observe RED**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest
```

Expected: compile failure because the coordinator does not exist.

- [ ] **Step 3: Implement the coordinator**

Read candidates using `MessageApprovalRepository.list(tenantId, conversationId)`, then filter by exact `sourceReceiptId` and `Status.PENDING`. Require exactly one candidate. Require `draftMayBeApproved()`, `allowedActions().contains(Action.APPROVE)`, and a nonblank stored draft. Construct a synthetic operator with a deterministic run-scoped subject, only `SCOPE_operator:write`, no roles, and only the replay tenant. Call:

```java
approvalService.act(operator, tenantId, conversationId, approval.id(), approval.conversationVersion(),
        "synthetic-operator:" + runId + ":" + sourceReceiptId,
        new MessageApprovalActionRequest(Action.APPROVE, approval.version(), approval.draftText()));
```

Require `status == "QUEUED"` and non-null `messageId`; return the exact persisted draft and receipt IDs. Do not catch fatal JVM errors. Map ordinary service errors to the content-free `APPROVAL_FAILED` status.

- [ ] **Step 4: Rerun coordinator tests and existing approval tests**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest
./gradlew test --tests com.ford.service.conversations.MessageApprovalServiceTest --tests com.ford.service.conversations.DefaultMessageApprovalEffectsTest
```

Expected: all selected tests pass; existing authorization/version/fresh-validation behavior remains covered by the service tests.

- [ ] **Step 5: Commit coordinator slice**

Run `git diff --check`, inspect the diff, then commit:

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApproval.java src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApprovalTest.java
git commit -m "test: approve eligible replay drafts through approval service"
```

---

### Task 3: Record synthetic approvals distinctly and wire bounded replay

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayYearlessAmbiguityAssertionsTest.java`

**Interfaces:**
- Add `ReplyOrigin.SYNTHETIC_OPERATOR_APPROVED` and telemetry `Outcome.SYNTHETIC_OPERATOR_APPROVED`.
- Add a `sourceReceiptId` field to the replay's internal `TurnRow` so the approval coordinator can bind to the exact inbound turn.
- For opt-in bounded mode only, convert a typed `NEEDS_OPERATOR` turn into a replay result only after `APPROVED` with a queued receipt. Keep the stored turn state unchanged and report it as `NEEDS_OPERATOR`.
- Track `processedTurns`, `committedTurns`, and `syntheticOperatorApprovedTurns` separately. Bounded completion text must state live Qwen replies and synthetic approvals separately, say approval was queued but not delivered, and must never call synthetic approvals committed Qwen replies.

- [ ] **Step 1: Add failing artifact and telemetry tests**

Add writer test showing a synthetic-origin turn is labeled `SYNTHETIC_OPERATOR_APPROVED`, and bounded completion reports one synthetic approval separately from live Qwen replies without saying all turns were committed by Qwen. Add telemetry test proving the synthetic reply origin/outcome serialize as distinct allowlisted enum values. Add a test preserving the standalone yearless-ambiguity assertion helper.

- [ ] **Step 2: Run focused tests and observe RED**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest --tests com.ford.service.conversations.LocalReplayYearlessAmbiguityAssertionsTest
```

Expected: compile/assertion failure because the synthetic origin/outcome and separate bounded counters are absent.

- [ ] **Step 3: Implement artifact semantics and bounded-only continuation**

Extend `TurnRow` query with `turn.receipt_id`; add a typed replay-turn result carrying conversation ID, answer text, reply origin, and the original persisted state. Around `awaitCommittedReply`, catch only `LocalReplayTurnFailure` whose row state is `NEEDS_OPERATOR`. If the opt-in is false, rethrow unchanged. If true, call the coordinator for the exact receipt. If result is not `APPROVED`, record a safe approval failure and stop; never synthesize a draft or change the database turn state. If approved, use the exact stored draft as the transcript/safety-check answer and label it `SYNTHETIC_OPERATOR_APPROVED`.

Run existing per-turn safety and no-handoff checks against that exact approved draft. Continue only after the approval receipt is `QUEUED`. Count it as processed and synthetically approved, not assistant-committed. At completion, require `committedTurns + syntheticOperatorApprovedTurns == expectedTurns`, preserve inbound-turn/outbound-message count checks, and report the counts separately. Keep the existing pre-replay assertions that `OutboxPublisher`, `OutboxDeliveryRouter`, and `ChannelDeliveryPort` beans are absent.

Remove the hardcoded live turn-31 call to `assertYearlessTrimClarification`: the current scenario fixture defines turn 31 as `DISCOVERY` (“La caja de la Ranger XLT...”), not a yearless trim lookup. Keep its independent focused test; do not apply that assertion to an unrelated turn or approved draft.

- [ ] **Step 4: Rerun focused tests and full unit suite**

Run the command from Step 2, then:

```bash
./gradlew test
```

Expected: both finish with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit replay integration slice**

Run `git diff --check`, inspect changes to ensure no approval text or raw provider/tool payload enters telemetry, then commit:

```bash
git add src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetry.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java src/integrationTest/java/com/ford/service/conversations/LocalReplayYearlessAmbiguityAssertionsTest.java
git commit -m "test: record synthetic operator replay approvals"
```

---

### Task 4: Verify gates, then run one bounded opt-in diagnostic

**Files:**
- Verify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Update after run: `docs/superpowers/specs/2026-10-29-synthetic-operator-approval-diagnostic-design.md`
- Update after run: `docs/superpowers/plans/2026-10-29-synthetic-operator-approval-diagnostic.md`

- [ ] **Step 1: Run focused non-live integration gates**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest --tests com.ford.service.conversations.LocalReplayYearlessAmbiguityAssertionsTest
```

Expected: `BUILD SUCCESSFUL`; no Qwen calls or synthetic webhooks.

- [ ] **Step 2: Verify harness delivery isolation before live replay**

In `runNaturalBuyerReplay`, retain and run assertions that all three bean maps are empty: `OutboxPublisher`, `OutboxDeliveryRouter`, and `ChannelDeliveryPort`. Confirm the bounded test resolves explicitly to `BOUNDED_LIVE_DIAGNOSTIC` and the opt-in parser is true only when requested. Do not create an alternate environment path that enables delivery.

- [ ] **Step 3: Run one bounded diagnostic with synthetic approval enabled**

Only after unit and non-live gates pass, use the approved Oct 25 snapshot, local Ollama `qwen3.5:4b`, local BGE preflight, disposable Testcontainers PostgreSQL, and clean environment. Set `FORD_QWEN_SYNTHETIC_OPERATOR_APPROVAL=true`, `FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC`, and `FORD_QWEN_DIAGNOSTIC_END_TURN=32`. Keep cloud disabled; leave seed/checkpoint variables and `FORD_QWEN_100_REPLAY_ENABLED` unset. Use the existing bounded command:

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
  FORD_QWEN_SYNTHETIC_OPERATOR_APPROVAL=true \
  ./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected only if each held draft is genuinely eligible: replay records turns through 32; every synthetic approval has a queued receipt, distinct transcript/telemetry origin, and no external delivery; safety/no-handoff checks remain active. If no eligible pending draft exists, stop at that turn and record the safe reason. Either outcome remains diagnostic-only and does not resolve the historical unsupported-price discrepancy.

- [ ] **Step 4: Record outcome and commit docs**

Record run ID, artifact paths, committed versus synthetically approved turn counts, queued approval receipts count, turns processed, handoff count only if actually recorded, and any safe stop reason. State explicitly that queued approval was not delivered and 100-turn acceptance remains paused. Run `git diff --check`, inspect docs, then commit:

```bash
git add docs/superpowers/specs/2026-10-29-synthetic-operator-approval-diagnostic-design.md docs/superpowers/plans/2026-10-29-synthetic-operator-approval-diagnostic.md
git commit -m "docs: record synthetic approval diagnostic outcome"
```

## Self-Review

- Configuration is explicitly bounded-only and opt-in (Task 1).
- Only one exact pending, approvable draft can be approved through the real service; ineligible cases stop (Task 2).
- The held turn remains `NEEDS_OPERATOR`; transcript/telemetry distinguish synthetic approval and counters (Task 3).
- Delivery isolation, TDD gates, local-only capped replay, artifact recording, and paused 100-turn acceptance are covered (Task 4).
- The historical turn-31 assumption was corrected: fixture turn 31 is `DISCOVERY`; yearless-ambiguity assertions stay in focused tests, not hardcoded to that scenario turn.
