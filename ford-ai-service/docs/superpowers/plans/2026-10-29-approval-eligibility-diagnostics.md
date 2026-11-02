# Approval Eligibility Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the opaque synthetic-approval `NOT_APPROVABLE` result with a safe, allowlisted eligibility reason, then rerun the local diagnostic only through turn 31.

**Architecture:** Keep approval eligibility and all safety checks unchanged. The integration-test coordinator will classify the first failed approval invariant as `DRAFT_NOT_SENDABLE`, `APPROVE_ACTION_NOT_ALLOWED`, or `DRAFT_MISSING`. The bounded replay records only this code in existing sanitized failure diagnostics; it never records draft/provider content. After unit and non-live integration gates pass, repeat the approved local replay with end turn 31.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito, Gradle, PostgreSQL Testcontainers, local Ollama.

## Global Constraints

- Synthetic approval stays opt-in and valid only in `BOUNDED_LIVE_DIAGNOSTIC`, capped at 32 turns.
- This follow-up run ends at turn 31; no turn 32 request.
- Never bypass `MessageApprovalService`, `APPROVE` authorization, send validation, freshness, or evidence rules.
- Never use edit, reject, takeover, review resolution, transcript seed, checkpoint, or database mutation to make the approval eligible.
- Keep `OutboxPublisher`, `OutboxDeliveryRouter`, and `ChannelDeliveryPort` absent before replay traffic.
- Use only the approved `oct25-candidate` snapshot, local Qwen/BGE, cloud disabled, and disposable PostgreSQL.
- No production writes or external delivery. 100-turn acceptance stays paused; historical unsupported-price discrepancy stays unresolved.
- Diagnostic reason codes may appear in test-only transcript/telemetry. Never expose draft text, raw provider/tool output, exception messages, or operator identity.

---

### Task 1: Classify approval eligibility failures with allowlisted codes

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApproval.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApprovalTest.java`

**Interfaces:**
- Replace the single `NOT_APPROVABLE` result status with `DRAFT_NOT_SENDABLE`, `APPROVE_ACTION_NOT_ALLOWED`, and `DRAFT_MISSING`.
- Add package-private `static Status eligibilityFailure(boolean sendable, boolean approveAllowed, String draftText)`.
- Deterministic precedence: not sendable; then `APPROVE` absent; then null/blank draft. Return `null` only when eligible.
- `approveIfEligible(...)` still returns `APPROVED` only after the real service returns a queued receipt.

- [x] **Step 1: Write focused failing tests**

Add tests for each failure reason and for the eligible case:

```java
assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(false, true, "Draft"))
        .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.DRAFT_NOT_SENDABLE);
assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, false, "Draft"))
        .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVE_ACTION_NOT_ALLOWED);
assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, true, " "))
        .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.DRAFT_MISSING);
assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, true, "Draft")).isNull();
```

Update the existing non-approvable draft test to expect `DRAFT_NOT_SENDABLE`. Confirm no failure case calls `MessageApprovalService.act`.

- [x] **Step 2: Run focused test and observe RED**

Run from `ford-ai-service/`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest
```

Expected: compile failure because `eligibilityFailure` and the reason statuses are not defined.

- [x] **Step 3: Implement reason classification without changing approval behavior**

Implement this helper:

```java
static Status eligibilityFailure(boolean sendable, boolean approveAllowed, String draftText) {
    if (!sendable) return Status.DRAFT_NOT_SENDABLE;
    if (!approveAllowed) return Status.APPROVE_ACTION_NOT_ALLOWED;
    if (draftText == null || draftText.isBlank()) return Status.DRAFT_MISSING;
    return null;
}
```

In `approveIfEligible`, after confirming one pending exact-receipt approval, call it with `approval.draftMayBeApproved()`, `approval.allowedActions().contains(Action.APPROVE)`, and `approval.draftText()`. Return that reason status if non-null. Leave service invocation, exact draft, tenant scope, idempotency key, and queued receipt checks unchanged.

- [x] **Step 4: Run coordinator tests**

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit eligibility classification**

Run `git diff --check`, inspect that the reason enum has only fixed codes, and commit:

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApproval.java src/integrationTest/java/com/ford/service/conversations/LocalQwenSyntheticOperatorApprovalTest.java
git commit -m "test: classify synthetic approval eligibility failures"
```

---

### Task 2: Rerun local diagnostic through turn 31 and record result

**Files:**
- Verify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Verify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTurnTelemetryTest.java`
- Update: `docs/superpowers/specs/2026-10-29-synthetic-operator-approval-diagnostic-design.md`
- Update: `docs/superpowers/plans/2026-10-29-approval-eligibility-diagnostics.md`

- [x] **Step 1: Run unit and focused non-live integration gates**

```bash
./gradlew test
./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest --tests com.ford.service.conversations.LocalQwenSyntheticOperatorApprovalTest --tests com.ford.service.conversations.LocalQwenReplayTurnResultTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenTranscriptHtmlRendererTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest --tests com.ford.service.conversations.LocalReplayYearlessAmbiguityAssertionsTest
```

Expected: both commands report `BUILD SUCCESSFUL`; focused integration command makes no Qwen calls.

- [x] **Step 2: Verify local prerequisites without writes**

Confirm the approved snapshot directory exists and local Ollama lists `qwen3.5:4b` and `bge-m3`. Do not change catalog state or contact cloud services.

- [x] **Step 3: Run one fresh bounded diagnostic, ending at turn 31**

Use a clean environment; all delivery beans must be absent before replay traffic. Keep cloud disabled and do not set seed, checkpoint, or 100-turn variables. Set `FORD_QWEN_DIAGNOSTIC_END_TURN=31` and `FORD_QWEN_SYNTHETIC_OPERATOR_APPROVAL=true` with the existing local command:

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
  FORD_QWEN_DIAGNOSTIC_END_TURN=31 \
  FORD_QWEN_SYNTHETIC_OPERATOR_APPROVAL=true \
  ./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected: run reaches turn 31 or safely stops earlier. If held approval is ineligible, transcript/telemetry show one fixed reason code; turn 32 is never sent. Never force approval to make the run pass.

- [x] **Step 4: Record verification truth and commit docs**

Record run ID, exact reason, live replies committed, synthetic approvals queued (likely zero), processed turns, artifact paths, and handoff count only if actually recorded. State whether `MessageApprovalService` successful-approval path was reached. Keep 100-turn acceptance paused and historical price discrepancy unresolved. Run `git diff --check`, inspect docs, and commit:

```bash
git add docs/superpowers/specs/2026-10-29-synthetic-operator-approval-diagnostic-design.md docs/superpowers/plans/2026-10-29-approval-eligibility-diagnostics.md
git commit -m "docs: record approval eligibility diagnostic"
```

## Self-Review

- Eligibility reason codes do not change any approval predicate or action (Task 1).
- Existing replay diagnostics persist only fixed enum names, not message/draft/exception content (Task 2).
- Rerun is fresh and local-only, capped at turn 31, with no delivery or 100-turn acceptance (Task 2).
- If this run still stops safely, report the reason; do not add eligibility or bypass behavior.

## Execution Record

- Reason-classification implementation committed as `cd20e6f`.
- `./gradlew test` passed. Focused non-live integration gates passed. Coordinator tests verified all three fixed reasons and eligible behavior.
- Fresh local diagnostic ended at turn 31. Run ID: `38dbb30597324e43997fba5a8a2c676b`; 30 live replies committed; turn 31 stayed `NEEDS_OPERATOR`; reason `DRAFT_NOT_SENDABLE`; zero approvals queued; turn 32 not sent.
- The run cannot say why the processor marked the draft unsendable; it did not persist which hold path/predicate caused the flag. No forced approval. No external delivery. Final aggregate handoff count unavailable.
- Transcript, HTML, and telemetry paths are recorded in the design spec. Historical price discrepancy unexplained; 100-turn acceptance remains paused.
