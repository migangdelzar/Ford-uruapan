# Chat Reliability and Quality Roadmap

**Date:** 2026-09-25  
**Status:** Proposed for review  
**Scope:** Production conversation reliability, provider resilience, memory/RAG correctness, response quality, and safe rollout

## Goal

Make every accepted customer message either complete as a durable, grounded response or enter a visible, recoverable failure state. Improve conversational continuity without allowing derived memory failures, provider throttling, or unavailable Ford sources to silently drop turns, invent current facts, or create duplicate outbound messages.

## Current architecture and evidence

- The application accepts inbound channel events and processes them through `ConversationWorkflow` with Spring Modulith.
- A conversation turn is accepted through the conversation repository, then assistant memory is written and `OutboundDeliveryRequested` is published.
- `JdbcConversationMemoryRepository` stores `facts_json` in PostgreSQL `jsonb` but currently supplies a Java string parameter. The observed production error is PostgreSQL rejecting that bind as `varchar`.
- `ConversationMemoryService` catches projection exceptions, but the repository write participates in the enclosing event transaction. Catching the exception does not clear Spring's rollback-only state, so the outer event handler can fail at commit.
- `conversation_message` is the transcript store; `conversation_memory` is a versioned, rebuildable projection. Existing designs already specify bounded projection and assistant-turn persistence.
- `ProviderRoutingChatCompletionPort` selects one provider for a call. `SpringAiAssistantService` converts provider exceptions into a fallback response; the observed OpenRouter 429 is not currently recovered through a safe alternate completion path.
- Ford information tools are wired through Spring AI callbacks and optional MCP. The system prompt requires source lookup for volatile facts; the runtime must verify tool availability and use, not just MCP startup connectivity.
- Response quality checks already reject certain internal payloads and repeated answers. The existing model-agnostic evaluator can be extended rather than replaced.
- Earlier approved designs cover bounded memory projection, transcript repair, and model-agnostic response quality. This roadmap hardens their production behavior and adds cross-component reliability; it does not repeat their feature work wholesale.

## Design

### 1. Preserve a durable turn boundary

Keep inbound acknowledgement fast and idempotent. Keep model and MCP network calls outside short database write transactions. Persist the accepted assistant turn and its outbound-delivery intent as one durable unit using the existing conversation and delivery mechanisms. A delivery retry must use the existing stable turn/idempotency identity so a provider timeout cannot create a second customer-visible reply.

The exact transaction split must follow the current Spring Modulith event publication and repository behavior. Before implementation, trace the actual transaction boundary and event retry semantics; do not add `REQUIRES_NEW` speculatively. The invariant is that a derived projection exception cannot roll back an already accepted conversation turn or its delivery intent.

### 2. Treat memory projections as derived, recoverable state

Use `conversation_message` as the source of truth. Correct JSONB writes using a PostgreSQL-compatible bind or explicit cast, and test against PostgreSQL rather than relying on H2's `varchar` schema. Projection updates remain version-aware and bounded. A projection error must be isolated from the primary turn transaction and surfaced with a structured failure metric/log. If an update is missed, it must be possible to rebuild the projection from the transcript without re-running model output or sending another message.

All transcript and projection queries must enforce the complete memory scope: tenant, conversation, channel, provider account, and participant where represented by the schema. Keep semantic memory disabled until transcript and projection behavior are measurable.

### 3. Make provider resilience explicit and bounded

Classify model failures into rate-limited, transient network, timeout, invalid request, authentication/configuration, and tool/source failure. For retryable failures, use bounded backoff and honor `Retry-After` when present. Do not blindly retry a free-tier model on 429. A fallback provider is eligible only when it satisfies the request's sensitivity policy and has the required read-only tools. If no safe provider remains, persist a clear recoverable turn failure or concise customer-safe response according to the existing channel workflow; do not expose provider internals or create a retry loop.

Provider retry/fallback must be coordinated with turn idempotency and outbound delivery. Do not retry a completed send because the model call or a later projection failed.

### 4. Ground volatile Ford information through available sources

For promotions, prices, availability, and vehicle data, the assistant must use the appropriate authorized MCP/catalog/knowledge tool result. Verify at startup and during diagnostics which expected read-only tools are actually available, then test invocation and result propagation through Spring AI. If a required source is unavailable or returns no current evidence, the answer must state that confirmation is unavailable and avoid supplying remembered or invented current facts.

Use existing tool authorization and knowledge retrieval boundaries. Do not add write-capable MCP tools, a second RAG stack, or a new planning agent in this work.

### 5. Improve conversational quality through evaluation

Keep deterministic behavior for privacy, authorization, sensitive data, required source grounding, and suppression of internal tool payloads. Avoid adding more lexical routing or phrase-specific response branches for normal conversation. Review the repeated-answer quality filter against multi-turn examples and use the existing evaluator to drive prompt and orchestration changes only when a scenario demonstrates a failure.

The evaluation set must include greetings, contextual follow-ups, customer topic changes, correction of a previous vague answer, promotion lookup with tool evidence, missing/unavailable RAG, repeated inbound webhook events, model 429, and memory projection failure. Use fixture tool responses for routine model evaluation; use production customer data only with explicit authorization, and never include secrets in test fixtures or reports.

### 6. Observe the complete message lifecycle

Add or enable privacy-safe tracing and metrics for inbound acceptance, event processing, memory read/write/projection, provider attempts and categories, MCP tool availability/calls, turn persistence, and outbound outbox delivery/retry/dead-letter. Correlate stages with stable internal message/turn identifiers. Do not log message bodies, provider tokens, MCP credentials, or raw tool payloads. Make projection failures distinguishable from primary-turn failures and expose provider rate-limit behavior without requiring raw response logging.

## Delivery phases and acceptance criteria

### Phase 0: Live incident triage

Read-only inspect app/database/cache health, current logs, and the affected conversation/outbox records before any replay. Do not manually resend a customer message until its persisted delivery state and idempotency key are known.

**Acceptance:** the affected turn is classified as persisted, pending, delivered, or failed; recovery is documented without risking duplicate outbound delivery.

### Phase 1: PostgreSQL and transaction reliability

Correct JSONB binding; align database integration coverage with production PostgreSQL; establish the primary turn/delivery transaction boundary; isolate the derived projection; verify full memory scope and idempotency.

**Acceptance:** a PostgreSQL integration test exercises a projection update; an injected projection failure cannot roll back an accepted turn/delivery intent; a duplicate inbound event does not create duplicate assistant transcript or outbound delivery records.

### Phase 2: Provider resilience

Add bounded, categorized recovery around provider selection and completion, respecting rate-limit signals and tool/sensitivity constraints.

**Acceptance:** automated tests cover 429 with and without `Retry-After`, transient timeout, non-retryable configuration failure, eligible/ineligible fallback, bounded attempt count, and no duplicate outbound delivery.

### Phase 3: Memory and source grounding

Verify tenant-scoped transcript retrieval, continuity and topic changes, tool inventory, tool invocation, and grounded answers for current Ford information. Keep memory projection rebuildable and semantic retrieval disabled unless separately justified by evaluation.

**Acceptance:** multi-turn tests prove follow-up resolution and isolation; promotion answers cite only fixture or live authorized source results; missing-source cases do not invent volatile information.

### Phase 4: Response quality, observability, and rollout

Extend the existing model-agnostic evaluator, enable privacy-safe lifecycle signals, run the test suite, then deploy an immutable image to the application host `emme-1`. Keep PostgreSQL/Redis on `emme-2`; do not deploy the application there unless the inventory says it is an application node. Verify service health and synthetic flows, retain the prior image for rollback, and inspect logs before declaring success.

**Acceptance:** the evaluation suite reports per-scenario outcomes; health, tool use, provider failure categories, memory projection outcomes, and outbound delivery states are visible without message or secret leakage; rollout has a tested rollback image and no unresolved critical errors.

## Error handling invariants

- Webhook acknowledgement does not imply successful assistant completion; asynchronous turn state must remain observable.
- A derived-memory failure cannot erase a persisted conversation turn or its outbound-delivery intent.
- Retries are bounded, categorized, and idempotent; successful outbound delivery is never repeated because of an upstream model or projection error.
- Volatile Ford facts are answered only from an authorized current source result.
- Missing tools/providers produce a safe and diagnosable outcome, not fabricated data or leaked internals.
- Tenant and conversation boundaries apply equally to transcript, projection, cache, and retrieval.

## Non-goals

- No broad conversation-pipeline rewrite or new agent framework.
- No new database or semantic-memory subsystem.
- No new MCP write operations, automatic customer-message replay, or human-handoff activation.
- No prompt rewrite solely for stylistic preference; prompt changes must be tied to evaluator evidence.
- No deployment to `emme-2` as an application host without inventory evidence.

## Risks and decisions to verify before implementation

- Spring Modulith's exact event listener transaction and retry behavior must be confirmed from the active configuration and persisted publication state.
- The PostgreSQL test mechanism must match the repository's current Gradle/Testcontainers setup; if no PostgreSQL integration fixture exists, add the smallest supported one.
- Safe provider fallback depends on available credentials, model terms, sensitive-data policy, and tool callback support; no fallback should be assumed from configuration names alone.
- Recovery of the incident's specific missed response must be based on its durable state and must not send a duplicate.
