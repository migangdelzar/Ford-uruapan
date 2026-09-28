# Adaptive Ford Lead Journey Design

**Date:** 2026-09-25
**Status:** Proposed for user review

## Objective

Give each Ford sales lead a durable, useful sense of progress across messages without turning the chat into a rigid questionnaire. The assistant must answer the lead's current request first, use verified Ford information for changing facts, then guide toward one relevant next action. A later message must resume from confirmed context instead of starting qualification over.

## Current project context

- `LeadStage` represents the broad CRM lifecycle (`NEW`, `QUALIFYING`, `QUALIFIED`, `CONVERTED`, `LOST`); it is not a sales-journey step.
- `LeadQualificationPolicy` infers broad lifecycle/temperature from lexical signals in the current message and recent memory. It must not become the source of truth for detailed sales progress.
- `conversation` and `lead` are tenant-scoped PostgreSQL records, but inbound chat does not currently create a CRM `lead` row. `LeadProfile` is a domain value but does not itself provide durable journey state.
- `ConversationWorkflow` loads transcript memory and invokes the assistant. Conversation memory is for dialogue continuity; it is not a reliable workflow checkpoint.
- Spring AI 2.0.1 and the existing `ChatClient`, MCP/tool authorization, Ford tools, conversation memory, and outbox are the project baseline. The final assistant result should carry both customer-facing text and a bounded structured journey proposal from the same tool-enabled `ChatClient` call; do not add a separate model round trip. Spring AI's tool-calling loop is an advisor-chain capability; this design does not require a second agent framework.

## Core design

Model the journey as **a current topic plus durable visited/paused topics and confirmed facts**, not a single mandatory stage number or a claim that a sales step is complete. The journey may move forward, pause, skip, or revisit topics. The model handles natural-language understanding and proposes the next useful step; application/domain rules validate facts, tool access, state updates, consent, and side effects.

Keep three concerns separate:

1. **Conversation memory:** recent turns and compact dialogue context for resolving references and avoiding repeated answers.
2. **Lead journey:** tenant/conversation-scoped confirmed facts, current topic, visited/paused topics, and any unresolved question/action; link the CRM `lead` when one exists.
3. **CRM lifecycle/qualification:** existing lead status, score, and temperature; these remain business reporting signals and do not drive a forced conversation script.

### Journey topics

The default Ford sales journey covers these topics, but does not require the lead to complete them in order:

1. **Vehicle discovery:** desired model/use, new or used preference when relevant, key constraints, approximate budget or timing only when useful.
2. **Vehicle match and current offer:** compare suitable vehicles and answer price/promotion/availability questions from an authorized, current source. This is a supporting topic, not a gate before a lead may ask about an offer.
3. **Financing or trade-in:** enter only when the lead asks or signals interest; explain estimates as non-binding and distinguish an estimate from lender approval or a trade-in appraisal.
4. **Test drive or appointment:** offer only when relevant; confirm only a real slot from an authorized scheduling source and obtain the required consent before creating it.

The assistant records which topic is active, which topics were visited or paused, and which facts are known. Topic history is not proof that an offer was verified, financing was approved, a trade-in was appraised, or an appointment was booked. Only an application-confirmed outcome may represent a completed external action. A declined topic can be recorded to avoid pressure. No proactive nurture message is sent by this feature.

### Per-message behavior

For each accepted inbound message:

1. Load the tenant-scoped lead journey and relevant conversation memory.
2. Identify the current message's intent, including a new topic, correction, direct question, explicit action request, or request to stop/escalate. The current message wins over an inferred old goal.
3. Answer the direct request first. For current Ford facts, call the appropriate authorized Ford/MCP/RAG source. If the source is unavailable, stale, or inconclusive, say that confirmation is unavailable and offer a practical next step; do not invent an offer, price, stock state, or appointment.
4. Merge only confirmed user facts into the durable journey. Record the accepted topic transition; a model-proposed inference without direct evidence stays unconfirmed. Tool results stay in the transcript/turn context unless a later approved integration explicitly persists a verified outcome.
5. Select at most one relevant next question or action. Skip facts already known; do not ask the same unresolved question again immediately. Let the lead skip, change direction, or return later without losing progress.
6. Persist the accepted assistant turn, its outbound intent, and its journey update with tenant scoping and optimistic version checks. Derive a stable assistant-turn idempotency key from the inbound provider message ID so redelivery cannot create a second reply, journey transition, or outbound action. A failed model/tool call must not advance the journey. Confirmed user facts may be captured only through the same durable, idempotent turn boundary.

Replies should sound natural and concise, answer first, and usually end with no more than one useful question. The assistant must not recite stages/topics or expose internal workflow state.

### Durable state contract

Persist one bounded journey snapshot in a focused `lead_journey` table, keyed by tenant and conversation, with an optional link to the CRM `lead`. A conversation is the available durable identity for an unqualified prospect; do not require creation of a CRM lead just to remember chat progress. Keep the table separate from the `lead` CRM profile so conversational progress does not become CRM lifecycle or an unbounded profile document. Validate the JSON snapshot's allowed keys and maximum size in the domain boundary. The logical snapshot contains:

- tenant and conversation identifiers, plus nullable lead identifier;
- current topic (nullable), visited topic keys, and paused topic keys;
- confirmed facts as typed keys/values with provenance (`USER`, `FORD_SOURCE`, or `OPERATOR`), reference identifier, and observation time;
- the last unresolved question/action key and retry count, so the agent can avoid repeating the same unresolved question on consecutive turns and can offer a skip/alternative;
- optimistic version, last processed inbound message ID, and update time.

Do not copy raw transcript text, credentials, payment-card data, government IDs, or detailed bank/credit data into this snapshot. Existing transcript storage remains the conversation history. Use only the minimum sales facts needed for continuity.

All reads and writes are scoped by tenant and conversation; when linked, verify the lead belongs to the same tenant and conversation. A conversation must never load another tenant's journey. Concurrent updates use optimistic locking; a stale update must not overwrite newer confirmed facts. Use the stable assistant-turn idempotency boundary for journey updates; add a unique per-conversation/per-provider-message receipt only if integration tests show the existing turn transaction cannot enforce it. Cross-channel identity matching and merging multiple conversations into one person/lead are out of scope.

## Agent and guardrail boundary

- The model is responsible for understanding free-form messages, handling interruptions naturally, composing a response, and proposing one next step.
- The model returns a typed result containing the reply and optional journey proposal. The application validates and applies the proposal only after the response is accepted. If structured conversion fails, use existing safe provider recovery and leave journey state unchanged.
- Existing Ford MCP/RAG tools remain the source of business facts. Existing tool authorization remains the gate on which tool families can run.
- Deterministic code is retained for security, tenant isolation, consent, input validation, tool authorization, supported state transitions, idempotency, source freshness checks, and appointment/financial disclaimers.
- Remove keyword-derived detailed journey progression from the design. Do not add phrase-specific normal-sales routing or a fixed topic-by-topic scripted response system.
- Keep tool loops bounded within the current turn. Persisted journey state enables continuation across future messages; it is not a request to replay or rewind tool steps.
- Do not add LangGraph4j for this first version. Reconsider a graph/checkpoint framework only if measured needs require long-running parallel branches, durable human interrupts, or multi-step work that must resume independently of a customer message.

## Failure behavior

| Condition | Required behavior |
|---|---|
| Provider unavailable/rate-limited | Follow the existing bounded provider recovery policy. Do not mutate journey progress unless a reply is successfully accepted. Preserve enough turn state for safe recovery; never create duplicate outbound delivery. |
| Ford source unavailable or stale | Do not claim current price, promotion, or stock. Explain the limitation and offer a check/alternative. Do not claim the source verified an offer. |
| User corrects a known fact | Replace the prior value with the new explicit value, retain provenance/time, and reconsider the relevant topic without restarting unrelated parts of the journey. |
| User changes topic | Answer the new request; preserve the previous topic as paused rather than deleting it. |
| User declines a question/action | Record the decline only as needed to avoid asking again; continue help without pressure. |
| Appointment/finance operation fails | Explain that it was not completed; do not record success or promise an outcome. |
| Concurrent or duplicate message | Use idempotency and version checks; preserve the winning state and suppress duplicate side effects. |
| Persistence failure | Do not claim progress was saved. Follow the established recoverable turn/outbox behavior and emit privacy-safe diagnostics. |

## Testing and acceptance

The feature is acceptable when:

- The same conversation can ask a vehicle question, return later about a promotion, then ask about financing without re-asking facts already captured; the state survives process restart even when no CRM lead row exists.
- A promotion question is answered before any unrelated qualification question, and only from a fixture/live authorized source result; a missing/stale source never produces fabricated details.
- A lead may jump directly to financing or a test drive, interrupt the journey, correct a fact, decline a question, and resume later without losing unrelated progress.
- Known facts and the unresolved question are loaded on a new turn and survive process restart.
- Provider/tool/persistence failures do not advance journey state or cause duplicate outbound messages/actions.
- Tenant isolation, optimistic concurrency, and duplicate event processing are covered with PostgreSQL integration tests where DB behavior is involved.
- Evaluations include Spanish (es-MX) multi-turn examples for topic change, short contextual replies, repeated-question suppression, direct promotion lookup, missing RAG evidence, correction, and appointment request.
- Logs/metrics reveal journey load/update/conflict outcomes and source-use outcomes without logging message bodies, contact details, or model/tool payloads.

## Scope and non-goals

- Backend conversation behavior and durable per-conversation journey state, optionally linked to a CRM lead; no new operator UI/API or cross-channel identity matching is required for the first slice unless implementation discovery proves one is necessary for safe operation.
- No proactive campaign/follow-up scheduler, CRM replacement, lender approval, automated vehicle reservation, or unverified appointment booking.
- No reactivation of human handoff as part of this design; the current deployment has human handoff disabled.
- No new model provider, RAG system, or write-capable MCP tool.
- No broad rewrite of chat memory, webhook acceptance, or outbox delivery.

## Open implementation decisions

1. Trace the actual turn transaction and implement/test a stable inbound-message-derived assistant-turn idempotency key within it. Add only the smallest missing receipt/recovery state needed to make journey writes safe; do not introduce an independent retry mechanism.
2. Verify which Ford MCP/RAG capabilities are actually available for promotion, inventory, finance estimates, and appointment lookup; unsupported actions remain informational-only.

## References

- Project baseline: `ford-ai-service/gradle/libs.versions.toml` (Spring AI 2.0.1).
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) describes tool calling as a composable advisor-chain loop; use existing project callbacks and authorization rather than adding a second orchestration framework.
- Existing project designs: `docs/superpowers/specs/2026-09-25-chat-reliability-and-quality-roadmap-design.md`, `docs/superpowers/specs/2026-09-25-conversation-memory-repair-design.md`, and `docs/superpowers/specs/2026-09-23-ford-crm-concierge-platform-design.md`.
