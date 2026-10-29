# Trusted evidence, advisor approval, and mandatory handoff

Status: Written specification approved in conversation. Implementation proceeds in independently verified increments.

## Scope and delivery boundaries

Deliver three independently verified increments:

1. Trusted comparison evidence handoff for the Qwen replay harness.
2. Selective message approval across ford-ai-service and Ford-web.
3. Mandatory human routing and takeover integration.

Backend workspace: the existing customer-choice-inventory-approval worktree.
Frontend repository: `/Users/miguelangeldelgadillozarate/Development/Ford-web`.
Create an isolated frontend worktree before changes; preserve existing work in both repositories.
Use the production webhook and conversation graph. Do not introduce a second graph or agents.
No deployment, production writes, emme-2 writes, or catalog import is authorized by this feature.

## 1. Trusted comparison evidence

### Observed failure

All-live run `372e7c6d19f842f696eee2fccdae94ce` stopped at turn 27.
The replay wrapper retrieved an exact comparison, but injected it only as memory text.
The adapter's independent tool tracker therefore required another tool call.
Qwen omitted tools twice; forced get_vehicle used a partial XLT identity without a year,
returned empty, and the adapter correctly withheld the answer. This establishes a
boundary mismatch as well as model tool omission; it does not prove model-only failure.

### Contract

Pass an immutable structured evidence envelope alongside the request, separately
from memory text. Bind it to tenant, conversation, current request identity,
source execution, catalog snapshot, exact model/year, and both complete trims.
Its factory accepts only successful, nonempty, complete live catalog comparison
results; counts, tool names, model text, and recorded traces are insufficient.
Reject missing trims, conflicting identities, mismatched requests, stale/reused
envelopes, and seeded origins. A request-bound envelope cannot be accepted by a
later turn, repair of another request, or another tenant/conversation.

Validated evidence satisfies retrieval for that comparison without forcing an
unrelated single-vehicle fallback. It does not disable response quality checks,
claim grounding, ambiguity handling, tool authorization, or safety policies.
Additional model tool calls still execute through existing authorized callbacks.
Absent/invalid envelopes retain the existing fail-closed behavior.

Keep provenance REPLAY_PREFETCH distinct from MODEL and RECORDED_SEED. Accepting
prefetched evidence must not fabricate a model-selected tool call. Production
requests without this envelope behave unchanged. Do not trust text markers in
memory or client-provided evidence metadata.

## 2. Response routing

The backend owns the route. Model suggestions can request review but cannot
authorize external sends, weaken evidence requirements, or resume the bot.

| Route | Initial scenarios | Customer-facing behavior |
| --- | --- | --- |
| Automatic | Verified ordinary catalog facts and routine questions | Existing automatic reply |
| Advisor review | Availability promises, negotiated discounts, financing commitments, uncertain/blocked responses | Hold proposed substantive message; show advisor reason and available draft |
| Mandatory handoff | Explicit human request, serious complaint/fraud/legal issue, sensitive financial documents, repeated inability to help | Pause automated substantive replies; queue advisor and send only a safe acknowledgment |

Apply precedence: mandatory handoff, then review, then automatic. Mere mention of
financing, a published promotion, or a general legal question is not itself a
financial commitment or serious legal issue. Missing catalog evidence alone is
clarification/review, not mandatory handoff. Existing customer consent and
privacy protections remain in force; sensitive documents are not copied into
drafts, logs, audit metadata, or handoff summaries.

Repeated inability means two consecutive failed substantive assistance turns;
successful assistance clears the counter. Infrastructure retries and memory
compaction calls do not increment it. Keep the threshold explicit and tested.

Existing tenant handoff settings must not silently permit an unsafe automatic
reply. If handoff execution is administratively unavailable, retain the review
hold and expose the reason to authorized advisors; do not pretend a ticket exists.

## 3. Advisor approval workflow

Use a dedicated live-message approval aggregate. Existing shadow drafts stay
non-sending; ACCEPTED shadow reviews must not gain a sending side effect.
Existing inventory approvals keep their inventory verification requirements.

Record tenant, conversation, source receipt, conversation version, draft revision,
reason code, permitted actions, evidence references, timestamps, and disposition.
Store a candidate only when permitted by the existing safety/privacy checks.
If generation was withheld, the UI may have no sendable draft: explain the reason
and offer safe editing or takeover instead of inventing an answer.

Pending review blocks automated substantive sends for that conversation until
resolved or superseded. A new inbound turn invalidates the old candidate and is
evaluated through the same routing policy; stale approval never releases it.
Restart, closure, takeover, or another advisor response also invalidates pending
AI drafts. Rejection never sends the candidate and leaves the conversation held
for advisor action; rejection is not implicit bot resumption.

### Advisor actions

- Approve and send: explicitly confirm the exact current revision and content.
- Edit and send: create a revised candidate, validate it, then explicitly confirm.
- Reject: terminally reject the candidate without creating an outbound message.
- Take over: reuse the existing takeover/claim path, cancel pending AI drafts,
  and enter HUMAN_PENDING/HUMAN_ACTIVE as appropriate.

Blocked/unsupported content cannot be sent unchanged with a simple approval.
Advisor editing must resolve the blocking condition or use the authorized manual
conversation workflow; an approval flag is not evidence. Never claim a financing
approval, discount authority, or verified inventory without the required authority.

### Concurrency and delivery

Authorize tenant access and write/handoff permissions server-side. Use optimistic
conversation/draft versions and stable idempotency keys. Validate state and consume
the approval atomically with message persistence and durable outbox creation.
Two advisors, duplicate clicks, retries, and approve-versus-takeover races must
produce at most one accepted disposition and one outbound intent.
Delivery retries reuse the same outbound intent. Do not call a channel directly
from the review controller. Preserve channel delivery-window restrictions.

Record actor, action, reason, revision, source receipt, and outbound intent ID.
Do not put candidate text or sensitive documents into audit event metadata.

## 4. Web experience

Reuse the existing conversation widget, confirmation dialog, composer, takeover
controls, API conventions, and translations. Display a Needs review card with
draft (when safe), reason, status, and Approve & send / Edit & send / Reject /
Take over. Mandatory handoff shows its reason and queue/assignment status rather
than offering approval to bypass handoff.

Confirm sending with an exact message preview. Disable duplicate actions while
pending. Report a successful action only after backend acknowledgment. Show stale
version/authorization/delivery-window errors and refresh authoritative state.
Do not display a queued message as delivered. Keep drafts visibly separate from
the customer-visible transcript. Support keyboard navigation and accessible labels.

Relevant frontend boundaries:

- `src/widgets/conversation/ui/ConversationWidget.tsx`
- `src/shared/api/operatorCrmApi.ts`
- `src/shared/ui/ConfirmDialog.tsx`
- `src/features/takeover/ui/BotControlSwitch.tsx`
- `src/server/auth/createBffApp.ts`
- `src/shared/i18n/index.ts`

Add narrowly scoped BFF routes, CSRF handling, and typed API responses. Never
broaden the BFF mutation allowlist to arbitrary backend paths.

## 5. Verification and acceptance

Cheapest feedback first; no overlapping Gradle runs against shared output paths.

1. Unit tests: trusted-envelope validation and adapter tool-omission regression;
   invalid evidence retains safety handling; ordinary production requests unchanged.
2. Unit tests: routing precedence, explicit human request, complaint/fraud/legal
   context, sensitive document signals, two consecutive failures, reset on success,
   normal prices/promotions/financing questions, and absent evidence without handoff.
3. Unit tests: approval transitions, rejected/stale/unsafe drafts, permissions,
   takeover and resumption guards, and delivery-window enforcement.
4. DB integration tests: concurrent approval and takeover, idempotent retry, atomic
   outbox writes, rollback, tenant isolation, and webhook holds during review.
5. Frontend Vitest tests and TypeScript check: previews, edit/reject/takeover,
   missing candidate, errors, duplicate clicks, stale state, and accessible controls.
6. Only after unit/integration/type checks: isolated UI E2E and focused live Qwen
   comparison diagnosis. A seeded checkpoint is diagnostic continuity, not live
   prior-turn evidence.
7. Fresh 100-turn all-live catalog acceptance remains separate from mandatory
   handoff scenarios. Keep its zero-seed, zero-handoff, evidence and safety gates.
   A held review is not counted as a successful automatic catalog reply.
8. Separate handoff-flow acceptance proves expected human routing and stopped bot.

Catalog import remains blocked until a trustworthy fresh all-live acceptance
passes and the export/import safety checks are independently verified.
