# LangGraph4j-Owned Ford Sales Conversation Design

**Date:** 2026-09-25  
**Status:** Approved by user; implementation in progress
**Supersedes:** The orchestration choice in `2026-09-25-adaptive-ford-lead-journey-design.md`. Its non-linear sales-journey and confirmed-fact requirements remain in force unless explicitly changed below.

## Objective

Make Ford sales conversations coherent across turns, grounded in authorized Ford/MCP/RAG data, and able to route a qualified prospect to a human without turning the chat into a rigid questionnaire. LangGraph4j owns the bounded conversation workflow. Spring AI continues to execute model calls and the existing authorized tools. The system records a durable sales journey, avoids repeated questions and side effects, and distinguishes a warm follow-up from a live handoff.

The requested deployment is to the app host `emme-1`, with the application verified against its PostgreSQL/Redis services on `emme-2`. The repository’s current inventory and mise tasks assign `emme-1` the app role and `emme-2` the data role; do not install a second application on the data host unless that inventory is deliberately changed and separately approved.

## Current project facts

- Runtime baseline is Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Modulith 2.1.1, PostgreSQL, Liquibase, and the existing Spring AI `ChatClient`/tool configuration.
- A tenant-scoped `lead_journey` snapshot and assistant-turn context integration already exist on the current feature branch. These are retained and reviewed as inputs to the graph; they are not replaced with graph checkpoint data.
- `ConversationWorkflow` is currently the application event handler for accepted inbound turns. It loads memory, calls the assistant, persists a bot turn/outbox intent, and separately handles handoff.
- Existing operator APIs can list and claim handoff tickets. An assistant handoff currently writes `HUMAN_PENDING` and a ticket. `HandoffNotificationListener` records `HANDOFF_NOTIFICATION_REQUESTED` as an audit action; it does not deliver an alert to an advisor. A ticket is therefore queued, not proof that an advisor has seen or accepted the conversation.
- Existing handoff states are `HUMAN_PENDING`, `HUMAN_ACTIVE`, and `CLOSED`; conversation states include `BOT_ACTIVE`, `HUMAN_PENDING`, and `HUMAN_ACTIVE`. Warm follow-up cannot safely reuse the current live-handoff transition because the bot must remain active.
- Deployment commands already exist: `mise run deploy:app` targets the `app` inventory group on emme-1, and `mise run deploy:data` targets the `data` group on emme-2. Secrets flow through the established deployment configuration and Ansible path; no hand-written SSH deployment is introduced.

## Architecture decision

Use a custom LangGraph4j `StateGraph` as the single owner of routing and execution order. Keep the graph finite and bounded per inbound message; it must not repeatedly revisit model or tool nodes without an explicit retry policy. Each new customer message starts a new graph invocation using tenant-scoped business state and the most recent durable checkpoint as appropriate. Conditional routing handles the current intent and handoff outcome; it does not force the lead backward through a prescribed stage.

Use LangGraph4j’s Spring AI core integration only where it provides a compatible adapter needed by the custom graph. Do not use its ready-made ReAct `AgentExecutor` as a second orchestration/tool loop. Existing Spring AI `ChatClient`, provider routing, callback authorization, Ford MCP tools, RAG, and response-quality checks remain behind graph nodes. LangGraph4j decides *which node runs next*; Spring AI performs *the model/tool operation requested by that node*.

Upstream LangGraph4j documentation distinguishes reusable `spring-ai-core` adapters for custom graphs from `spring-ai-agent`’s ready-made ReAct executor. Upstream documents the 1.9.1 line and Java 17+ support, but the project’s Spring AI 2.0.1 / Spring Boot 4.1.0 combination must be verified with a dependency-resolution and compile/runtime spike before pinning an artifact. If the optional adapter is incompatible, use LangGraph4j’s core graph APIs with the existing Spring AI application service; do not downgrade the project BOM or duplicate the tool loop to force compatibility. [LangGraph4j Spring AI integration](https://github.com/langgraph4j/langgraph4j/blob/1c30771c7c7876e79ca3b08904dcbbbba75924d/spring-ai/README.md) · [LangGraph4j Spring AI Agent README](https://github.com/langgraph4j/langgraph4j/blob/1c30771c7c7876e79ca3b08904dcbbbba75924d/spring-ai/spring-ai-agent/README.md)

## Graph contract and turn flow

Each graph state is an execution envelope, not a new business database. It contains the tenant/conversation scope, inbound event identity, current user message for this invocation, loaded bounded conversation context, loaded lead-journey snapshot/version, extracted current-turn intent/evidence, authorized tool results needed for this turn, response candidate, qualification decision/reasons, selected route, and commit outcome. Do not put credentials, raw MCP secrets, payment data, or government identifiers in checkpoints. Avoid copying the full transcript into graph state; use the existing transcript and bounded memory services.

The current user message, response draft, and tool payloads are invocation-only data and must be excluded from serialized checkpoints. Persist only stable tenant/conversation/inbound references and safe node/route/version metadata; nodes reload the current inbound body from its tenant-scoped canonical transcript record when resuming. If a checkpoint cannot be resumed without storing message content, restart that idempotent graph invocation from the safe beginning rather than persisting raw content.

The normal graph is:

```text
accepted inbound event
  → load scoped transcript context + lead journey + checkpoint
  → understand current request / explicit human request / consent
  → retrieve authorized Ford facts when needed (MCP/RAG)
  → compose and validate one customer response with Spring AI
  → assess fit and buying intent; produce explainable route and evidence
  → prepare bounded journey/handoff/follow-up changes
  → atomically commit idempotent assistant turn + business state + outbox
  → finish, leaving checkpoint only for resumable execution state
```

The latest message takes precedence over inferred prior goals. Promotion, price, availability, financing, and vehicle facts must use the appropriate current authorized Ford source. A missing, stale, or inconclusive source yields a clear limitation, not a fabricated fact. The graph asks at most one relevant next question and does not repeat a previously answered question merely to satisfy a stage. Current journey topics remain optional and interruptible: vehicle discovery, offers/pricing, financing/trade-in, and test drive/appointment.

Model interpretation may propose intent, topic, or fact changes. Only explicit user statements, verified Ford-source results, or operator-confirmed outcomes can become confirmed facts; model inference alone remains unconfirmed. Application/domain policies remain authoritative for tenant isolation, consent, validated fact provenance, allowed state transitions, tool authorization, data minimization, idempotency, handoff eligibility, and whether an external action actually succeeded. Ordinary conversational routing must not become another pile of phrase-specific branches.

## Business state, checkpoints, and persistence

Keep these stores distinct:

| Data | Canonical owner | Purpose |
|---|---|---|
| Transcript, accepted assistant reply, delivery intent | Existing conversation tables and outbox | Customer-visible history and reliable delivery |
| Confirmed sales facts, topic/visited/paused topics, unresolved question | Existing tenant-scoped `lead_journey` snapshot (evolve additively if needed) | Customer continuity and no repeated qualification |
| CRM lead score/lifecycle | Existing lead/CRM records | Sales reporting and prioritization; not a forced script |
| Graph checkpoint | LangGraph4j saver in PostgreSQL, isolated tables/schema and retention policy | Resume interrupted execution/debuggable orchestration; never replace transcript or CRM |
| Live human handoff | Existing handoff ticket/conversation state, extended only through additive migration | Priority operator queue and bot pause/takeover lifecycle |
| Accepted warm follow-up | Separate tenant-scoped follow-up aggregate/table | Accepted lower-priority request while the bot remains active; it must not share live-handoff state transitions |

Use a stable thread key derived from both tenant and conversation identity; no cross-tenant checkpoint reuse. Keep PostgreSQL the durable source for graph checkpoints rather than Redis-only state. Review LangGraph4j’s PostgreSQL saver schema, serializer, upgrade/rollback behavior, and concurrency semantics before adoption. Upstream documents saver V2 as incompatible with V1 and notes that thread identity/cleanup need deliberate handling; do not run its schema setup blindly against business tables. Manage saver tables separately from Liquibase-owned business tables with a documented, versioned lifecycle and tested backup/restore. [PostgreSQL saver V2 contract and limitations](https://github.com/langgraph4j/langgraph4j/blob/1c30771c7c7876e79ca3b08904dcbbbba75924d/langgraph4j-postgres-saver/SAVER_V2.md)

The existing journey remains the canonical continuation state across customer messages. Checkpoints are execution state only; losing/rebuilding a checkpoint must not erase confirmed sales facts or transcript. Persist the accepted assistant message, accepted journey change, handoff/follow-up decision, and outbound intent under a single idempotent turn boundary wherever their repositories permit a common transaction. Keep model/MCP network calls outside the short database write transaction. Derive stable assistant/outbox idempotency from the accepted provider inbound ID and tenant/conversation scope. Duplicate inbound delivery must not duplicate the assistant response, follow-up, ticket, or notification intent.

Schema changes are additive Liquibase migrations. No table drop, destructive reset, production data rewrite, or tenant-id change is part of this design. PostgreSQL-backed tests must validate JSONB/DDL, optimistic concurrency, checkpoint reopen/recovery, transaction rollback, and idempotency.

## Sales qualification and routing policy

Do not treat a single lexical score as calibrated conversion probability. Represent fit and buying intent/engagement separately, retain the reason codes and safe evidence supporting a classification, and keep the current rule signals only as explainable initial inputs until evaluated against dealership outcomes. User corrections supersede inferred facts. The model can interpret natural language, but a deterministic policy validates routing and side effects.

Routing outcomes:

| Outcome | Trigger | Behavior |
|---|---|---|
| `AI_CONTINUE` | Normal information request or no reliable escalation signal | Answer the current request; preserve journey and continue AI. |
| `WARM_FOLLOW_UP_OFFERED` | Meaningful but non-urgent purchase interest | Offer an optional advisor follow-up once per relevant decision window. Record an offer/decline so it is not repeated. AI continues. Create a lower-priority follow-up item only after explicit customer acceptance; do not pause the bot or send an unsolicited external message. |
| `HOT_LIVE_HANDOFF` | Strong near-term purchase/appointment intent according to explainable qualified signals | Create/ensure a high-priority durable handoff ticket and transition the conversation to `HUMAN_PENDING`; the bot stops generating normal responses until an operator claims/returns control according to existing lifecycle. Do not tell the customer an advisor has joined until the system confirms claim/assignment. |
| `EXPLICIT_HUMAN_REQUEST` | Customer directly asks for a person/advisor | Immediate priority handoff regardless of score, subject to feature flag and operational availability. Acknowledge clearly and do not continue automated qualification. |
| `SAFE_FAILURE_OR_RETRY` | Provider/tool/state/commit fails | Do not advance journey or create uncommitted routing side effects; use the existing bounded retry/recovery behavior and privacy-safe failure state. |

The `FORD_HUMAN_HANDOFF_ENABLED` flag remains authoritative. If disabled, never create a false claim of transfer; provide a concise transparent response and preserve an auditable safe outcome. Before enabling live handoff in production, verify an operator can observe and claim the durable queue. A pending ticket is not an external advisor notification. The first implementation must use an already configured operator-facing mechanism if one is verified; otherwise, add a narrow notification port and durable outbox event only after identifying the existing target/channel. Do not introduce a new vendor or expose customer PII in alerts by assumption.

For warm follow-up, use a separate tenant-scoped aggregate with `OFFERED`, `DECLINED`, `QUEUED`, `CLAIMED`, and terminal states, unique/idempotent against the inbound turn. An accepted follow-up is a consented queue item; while it waits, the conversation remains `BOT_ACTIVE`. A declined offer is suppressed for the remainder of the current buying-intent window and may be offered again only after materially changed intent, never on consecutive turns. No background nurture or proactive customer message is added.

## Human queue and operational behavior

Preserve the existing operator list/claim, pause, resume, and audit contracts. Add priority/reason/consent metadata without breaking existing API consumers. Ensure queue listing is tenant-scoped, claim is optimistic/versioned, ticket creation and outbox event are idempotent, and an operator claim—not ticket creation—marks an advisor as active. A claimed HOT conversation pauses automated sends; resumption requires an explicit handback and a valid state transition. WARM follow-up does not monopolize the live conversation or block other AI replies.

The present audit-only `HandoffNotificationListener` must not be described as an alert. The implementation must either connect it to an already configured operator alert surface verified in repository/deployment evidence or deliver a durable observable queue that operators already consume. If no such surface exists, report the specific remaining external integration and keep customer wording truthful; do not mark deployment acceptance as “advisor notified” without end-to-end evidence.

## Failure, privacy, and recovery

| Failure | Required behavior |
|---|---|
| Model/provider unavailable or rate-limited | Use existing bounded provider policy; do not run an unbounded graph loop. Do not commit journey advancement for a failed response. |
| Ford MCP/RAG unavailable or no current evidence | Say the information cannot be confirmed now; do not invent price, offer, stock, finance approval, or appointment availability. |
| Tool result malformed/unauthorized | Reject it at the existing authorization/validation boundary; do not pass unsafe output into customer response or checkpoint. |
| Conversation/journey version conflict | Reload and make a bounded retry only if the inbound turn is still eligible; otherwise retain the winning state and avoid duplicate side effects. |
| Checkpoint write/read failure | Business transcript/journey correctness remains independent. Fail or recover according to a bounded explicit policy; do not silently start a different tenant/thread or lose accepted work. |
| Duplicate webhook/event | Return/complete idempotently with no duplicate reply, ticket, follow-up, or delivery. |
| Follow-up acceptance ambiguous | Ask one clarifying question; do not enqueue until explicit acceptance. |
| Warm offer declined | Persist suppression for the configured decision window; AI continues. |
| Handoff disabled or operator queue unavailable | Do not claim transfer. Give a transparent response and preserve the request for safe handling only if persistence succeeds. |
| Handoff ticket created but nobody has claimed it | Keep truthful `HUMAN_PENDING`/queued status; do not claim a live advisor joined. Make queue age/claim state observable. |

Checkpoints and logs must not contain provider/API secrets, raw payment or identity data, or full tool payloads. Log only correlation IDs, graph/node outcome, provider/tool categories, route/reason codes, version/conflict/retry state, and safe timing metadata. Enforce bounded checkpoint retention and deletion aligned with conversation/tenant retention requirements.

## Rollout and deployment acceptance

1. Verify the baseline branch and working tree; run the unit and PostgreSQL integration suite before changing schema or graph dependencies.
2. Add the graph behind a runtime feature flag with the existing workflow available as rollback. Keep `FORD_HUMAN_HANDOFF_ENABLED` as an independent kill switch. Because this approved behavior includes HOT/direct queueing and accepted WARM follow-up, set it to `true` in the managed deployment configuration for this rollout only after operator list/claim is verified; propagate it through the existing secrets task, never an ad-hoc SSH edit.
3. Verify dependency resolution for Java 25 + Spring Boot 4.1.0 + Spring AI 2.0.1 + the selected LangGraph4j modules. Build the graph and saver with an integration test before enabling it for inbound production traffic.
4. Back up the emme-2 PostgreSQL data using the existing backup tasks; deploy additive database/saver schema safely; prove rollback keeps prior app and data readable.
5. Publish the immutable ARM64 app image to GHCR with `mise run release:publish`, then deploy that exact image reference to emme-1 with `mise run deploy:app` and the existing Ansible/secret-propagation mechanism. Do not manually SSH, alter secrets outside the existing task, or reset production data.
6. Verify emme-1 readiness, database connectivity/migrations and Redis-backed behavior on emme-2, correct OpenRouter/provider selection, authorized Ford MCP/RAG tool inventory and invocation, inbound webhook acceptance, grounded promotion lookup, multi-turn memory/continuation after process restart, warm offer/decline/acceptance, hot/direct human queue creation, operator list/claim, outbound delivery, duplicate-event idempotency, and privacy-safe logs.
7. Inspect app, database, outbox, and handoff logs before declaring success. Keep the previous image and documented rollback command. If a required downstream capability (Ford source, advisor queue/alert, credentials, or provider) is absent, identify it specifically and do not claim the whole user flow works.

Deployment is complete only when the app is healthy on emme-1 and its required data dependencies are healthy and exercised on emme-2. Because emme-2 is a data host in the current inventory, the app itself is not deployed there.

## Acceptance criteria

- Graph routing is owned by LangGraph4j, while Spring AI and its authorized tools remain the only model/tool execution path; no second ReAct/tool loop is introduced.
- User can move between vehicle, promotion, finance/trade-in, and appointment topics without repeating known questions; a new inbound turn resumes durable context after restart.
- Promotion and other volatile Ford answers use authorized current tool evidence and do not hallucinate when evidence is unavailable.
- A duplicate inbound event produces at most one accepted assistant reply, journey update, outbound intent, and follow-up/handoff item.
- Warm lead is offered human follow-up at most once per decision window; acceptance creates a lower-priority consented follow-up item while the bot remains active; decline creates no item and the bot remains active.
- Hot signals and explicit human requests use the high-priority live queue; explicit human request bypasses lead score. `HUMAN_PENDING` does not imply a human has claimed the conversation.
- Operators can securely observe and claim a live ticket; alerts (if configured) are durably produced, deduplicated, privacy-safe, and tested end to end.
- Disabling human handoff creates no ticket or false transfer claim; safe behavior is tested.
- Tenant scope, authorization, transaction boundary, optimistic concurrency, checkpoint failure, and rollback are covered by tests using the actual PostgreSQL behavior.
- The exact production deployment is verified via existing mise/Ansible commands with logs and rollback image checked before declaring success.

## Out of scope

- Installing the app on emme-2 while the committed inventory defines it as PostgreSQL/Redis data host.
- Replacing Spring AI, the provider stack, Ford MCP/RAG service, transcript store, or CRM.
- Rewriting all database schemas or dropping/resetting production tables.
- Cross-channel person identity resolution, automatic financing decisions, inventory reservations, or appointment booking without an authorized write tool.
- Unsolicited marketing/nurture messages or warm follow-up without explicit acceptance.
- Claiming a human has received/accepted a handoff based solely on a pending ticket.

## Implementation sequencing constraint

The next artifact after this reviewed design is a task-by-task implementation plan. The first implementation slice is a compatibility/proof-of-concept graph using the real dependency BOM and PostgreSQL saver, before broad rewiring. Subsequent slices migrate one vertical flow at a time: graph boundary and node tests; scoped state/checkpoint; transactional turn/outbox integration; warm/hot routing and operator queue; evaluations/observability; then staged emme deployment and live verification. Do not start broad schema or conversation rewrites before the compatibility and baseline tests pass.
