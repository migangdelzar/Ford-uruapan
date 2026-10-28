# Advisor Approval Backend Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, task-by-task. Steps use checkbox syntax. No delegated implementation.

**Goal:** Enforce selective advisor approval and mandatory human handoff behind the already-gated web controls.

**Architecture:** Deterministic routing precedes the existing conversation commit. A separate immutable approval aggregate binds actions to tenant, conversation, receipt, draft revision, and conversation version. Persistence serializes conversation changes and atomically consumes approvals with durable outbound intents; shadow reviews remain non-sending.

**Tech Stack:** Java, Spring, JDBC/PostgreSQL, Liquibase, JUnit/Mockito, existing operator commands and handoff APIs.

## Global Constraints

- One production webhook and conversation graph; no second graph or agents.
- Approval never replaces missing evidence or discount/finance/inventory authority.
- Server policy owns allowed actions. Mandatory handoff cannot be approved into a bot reply.
- Rejection leaves review held; takeover stops bot replies; resumption is explicit.
- No production writes, emme-2 writes, deployment, or catalog import.
- Frontend flag remains false until backend unit, DB concurrency, and webhook integration tests pass.
- Existing unrelated backend work must not be staged or overwritten.

## Task 1: Deterministic routing and approval aggregate

Create `src/main/java/com/ford/service/conversations/application/AdvisorResponseRoutingPolicy.java`, `src/main/java/com/ford/service/conversations/domain/MessageApproval.java`, and corresponding tests under `src/test/java/com/ford/service/conversations/`.

- [x] Add failing routing tests: human request, serious complaint/fraud/legal signal, sensitive document, two consecutive substantive failures, review-only inventory/discount/finance commitment, blocked response, model review suggestion, and ordinary verified questions. Counter represents completed substantive turns, not provider retries/compaction.
- [x] Implement `decide(Signals)` returning typed Route/Reason with precedence mandatory handoff, advisor review, automatic. Inputs are backend-derived signals, never browser/model approval flags. Policy does not inspect or store document text.
- [x] Add failing domain tests for exact draft approval, confirmed authored replacement, missing/unapproved draft, stale approval/conversation versions, mandatory takeover-only actions, rejection retaining takeover, supersession, and a queued transition requiring an outbound receipt and advanced conversation version.
- [x] Implement immutable `MessageApproval.pending`, `allowedActions`, `prepare`, `complete`, and `supersede`. Prepared action binds the exact aggregate instance and preserves exact confirmed text. No external send happens in the domain. Override content-bearing toString methods so drafts never leak to ordinary logging.
- [x] Run `./gradlew test --tests com.ford.service.conversations.AdvisorResponseRoutingPolicyTest --tests com.ford.service.conversations.MessageApprovalTest`; observed missing-feature red before code, then green.

## Task 2: Transactional approval actions

Create `MessageApprovalService`, `MessageApprovalRepository`, and `MessageApprovalSendValidationPort` in conversations application/ports; create API DTOs `MessageApprovalView`, `MessageApprovalActionRequest`, and `MessageApprovalActionResult` matching the web contract. Add `MessageApprovalController` under the existing conversation API.

- [ ] Test read/write and handoff permissions inside the service as well as controller annotations; wrong-tenant or stale source receipt never sends. Null/invalid action/text/version/key must fail before effects.
- [ ] Implement GET `/api/v1/conversations/{conversationId}/message-approvals` and POST `/{approvalId}/actions`, using tenant validation, If-Match conversation version, expectedApprovalVersion body field, CSRF through existing security, and stable Idempotency-Key. Reject/Takeover carry null exactText. APPROVE must exactly equal the stored sendable draft; EDIT is an explicit authored replacement requiring fresh validation.
- [ ] Inside one transaction lock the conversation first, then approval; require source receipt/current version. Reserve/replay an actor-and-tenant-bound idempotency record with action/text/version hash. Consume the pending disposition with CAS, call existing operator command persistence/outbox for sends or an API-boundary takeover adapter, save the actual receipt, and audit content-free IDs/reason/revision/actor. Any failure rolls back approval, command reservation, audit, and outbox together.
- [ ] Return QUEUED only after a real accepted non-internal outbound receipt; never report delivered. Do not reinterpret shadow ACCEPTED as permission to send. No direct channel client calls.

## Task 3: Persistence and authoritative hold

Create a new migration after current `037` with approval history, content-free action audit, and a conversation-scoped active hold. Add a JDBC approval repository. Do not edit released migration checksums.

- [ ] Add DB tests: tenant isolation; duplicate-key retries; two advisors approving concurrently; approve-versus-takeover; stale inbound/restart/closure; rollback after outbox failure; rejection retaining hold; and one effective disposition/outbound intent.
- [ ] Guard bot commits against active holds and increment conversation version when holding/invalidation occurs so in-flight generations cannot commit stale replies. Use consistent conversation-before-approval lock order. New inbound invalidates the old draft, remains visible in transcript, and is evaluated for mandatory handoff without releasing unsupported messages.
- [ ] Ensure ordinary operator messages, restart, closure, and existing takeover invalidate pending AI drafts atomically, not through best-effort UI follow-up calls. Retain existing channel-window restrictions and inventory verification TTL/authority.

## Task 4: Pipeline routing and validation

Modify `DefaultConversationTurnProcessor` and `ConversationTurnCommitService` through narrow collaborators, not another graph. Connect the pure policy to current customer-authorized handoff, complaint/context signals, document-resolution metadata, consecutive failure ledger, and generated commitment/evidence status.

- [ ] Test mandatory routes happen before model processing of sensitive documents; safe acknowledgment only, no sensitive content copied into draft/log/audit/handoff summary.
- [ ] Test ordinary published prices/promotions and illustrative financing do not imply a commitment or automatic handoff. Missing evidence alone means clarification/review; two consecutive failed substantive turns escalate. Success clears failure count.
- [ ] Validate exact send text against refreshed evidence and authority. If an existing blocking condition remains, omit APPROVE and reject the action server-side. Advisor-authored replacements must pass validation; takeover permits normal authorized manual workflow rather than bypassing approval checks.
- [ ] If handoff is administratively unavailable, retain review hold and expose an explicit reason; never claim a ticket exists or send an unsafe automated reply.

## Task 5: Integration and rollout gate

- [ ] Run backend unit tests before DB integration, then frontend tests/typecheck/build in `Development/Ford-web/.worktrees/advisor-message-review`.
- [ ] Run end-to-end review/edit/confirm/reject/takeover and race flows only after those gates pass. Mandatory handoff acceptance is separate from catalog replay zero-handoff acceptance.
- [ ] Only then enable web review controls in an isolated test environment. No deployment implied.
- [ ] Restart cancelled fresh 100-turn catalog acceptance as a separate fresh all-live run after source changes stabilize. Cancelled/seeded/fixture runs never satisfy acceptance; catalog import stays blocked pending report review and separate import safeguards.

## Verified foundation checkpoint

Task 1 and the application-service core of Task 2 are implemented. Added typed view/action/receipt DTOs, MessageApprovalRepository and MessageApprovalEffects contracts, and an intentionally unregistered MessageApprovalService. The effect boundary must validate fresh sendability and join the eventual persistence transaction; no concrete effect or repository is installed yet.

Observed red/green tests for the new service and extra identity/size guards. Broad unit selection (`conversations.*`, `ai.*`, `architecture.*`) passed: 898 tests, zero failures/errors/skips. Separate scoped reviews found no critical/important issues in the domain and service foundations. They did not run DB tests and do not prove rollback or concurrent consumption.

Still required before activation: controller registration, migration/JDBC repository, concrete validated outbox and takeover effects, hold/invalidation wiring, mandatory-signal collection and persisted failure counting, DB rollback/concurrency tests, API/webhook integration, and end-to-end verification. No frontend/backend approval feature was enabled and no fresh all-live acceptance was claimed.
