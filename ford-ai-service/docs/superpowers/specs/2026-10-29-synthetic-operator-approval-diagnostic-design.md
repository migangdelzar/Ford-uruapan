# Synthetic Operator Approval for Bounded Diagnostics

## Purpose

Allow the bounded local Qwen diagnostic to continue past a `NEEDS_OPERATOR` turn when the application has created a safe, explicitly approvable assistant draft. This exercises the existing operator approval path for diagnostic coverage. It does not bypass why the turn needed review, simulate approval for an unsafe draft, or make the replay production acceptance.

## Current Behavior

The bounded replay stops when `awaitCommittedReply` observes a turn state other than an accepted/committed state. `NEEDS_OPERATOR` can be produced after a response disposition holds an assistant reply for operator review. `MessageApprovalService` supports an `APPROVE` action only for pending approvals that expose that action. Its normal effect validates and queues the exact current draft through `ConversationCommandService`; it does not itself deliver a message. The diagnostic application context already asserts that `OutboxPublisher`, `OutboxDeliveryRouter`, and `ChannelDeliveryPort` beans are absent.

Resolving a `ConversationReviewService` review item is not equivalent to approving a held message, so this design uses the message-approval API, not the review-resolution endpoint.

## Design

### Opt-in boundary

- Add an explicit `FORD_QWEN_SYNTHETIC_OPERATOR_APPROVAL=true` opt-in.
- Permit the flag only with `FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC` and its validated 1–32 end-turn bound. Reject it in every other replay mode.
- Default remains current behavior: `NEEDS_OPERATOR` stops the diagnostic.
- Before replay starts, assert there is no outbox publisher/router or channel delivery port. Keep outbound delivery disabled for this harness.

### Fail-closed approval flow

When the replay observes `NEEDS_OPERATOR` and the opt-in is enabled:

1. Find the message approval for the exact tenant, conversation, and inbound receipt.
2. Continue only when exactly one approval exists, it is `PENDING`, and its allowed actions include `APPROVE`. Require the exact nonblank draft from that approval. Do not use `EDIT`, `REJECT`, or `TAKEOVER` as substitutes.
3. Invoke the existing `MessageApprovalService` with a synthetic test operator limited to the disposable replay tenant and a run-scoped idempotency key. Preserve current version checks, fresh send validation, audit, and approval persistence.
4. Require a successful `QUEUED` approval receipt. Do not claim the inbound turn became `REPLY_COMMITTED`; it remains a held turn with an operator-approved queued draft. Any missing, multiple, stale, non-approvable, or failed approval stops the replay without bypass.
5. Continue the bounded scenario only after that explicit receipt. Run the existing response safety checks against the exact approved draft and the turn's evidence.

No production configuration, operator identity, or delivery adapter is used. The synthetic operator exists only in the integration harness. The normal authorization and approval service logic remains active.

### Artifact semantics

- Add a distinct reply origin such as `SYNTHETIC_OPERATOR_APPROVED`; never label the approved draft `LIVE_QWEN` or `REPLY_COMMITTED`.
- Track operator-approved turns separately from assistant-committed turns in transcript and telemetry. Report the original `NEEDS_OPERATOR` outcome and approval receipt status without persisting credentials, raw tool payloads, or exception messages.
- A run with any synthetic approval remains diagnostic-only and cannot satisfy bounded replay acceptance or the 100-turn gate.
- If the application lacks an eligible pending approval, keep the existing stop behavior and record the safe reason.

## Alternatives

- **Resolve the review item and continue:** rejected because review resolution does not approve or queue the held customer reply.
- **Force an assistant commit or mark the turn committed in the test database:** rejected because it bypasses version, evidence, and approval checks and creates false acceptance evidence.
- **Use the real message approval service with a bounded synthetic operator and disabled delivery (selected):** exercises existing checks and records a real queued approval in disposable state while preventing external delivery. It still does not prove delivery or explain the original review trigger.

## Acceptance Criteria

1. Synthetic approval is off by default and invalid outside bounded diagnostic mode.
2. With opt-in, only one current pending approval that allows `APPROVE` can proceed; all other cases fail closed.
3. The normal `MessageApprovalService` and exact-draft approval path are used, with run-scoped idempotency and existing authorization/version/send-validation checks.
4. The replay harness proves no external delivery components are present before it can approve anything.
5. Artifacts distinguish the synthetic operator-approved draft from a live assistant commit and report separate counts.
6. Safety and no-handoff checks remain enabled. An approval receipt is not reported as zero handoffs or as an assistant-committed turn.
7. Tests use the disposable application context only. No production data, production operator, remote delivery, or 100-turn acceptance is used.

## Verification Boundary

Run unit and focused non-live integration tests first. If a live bounded diagnostic is later authorized, cap it at 32 turns and use only the approved local snapshot, local Qwen/BGE, and disposable PostgreSQL. Keep the historical unsupported-price discrepancy and 100-turn acceptance gate explicitly unresolved unless independently explained.
