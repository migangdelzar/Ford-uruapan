# Enterprise Agentic Conversation Runtime Design

**Status:** Approved design; implementation not started

**Date:** 2026-09-25

## Goal

Define a reliable, model-agnostic conversation runtime for the Ford WhatsApp
concierge. Preserve a natural, model-led conversation while making turn
processing durable, ordered, bounded, privacy-aware, and recoverable. Spring
Modulith remains the internal event backbone; Spring AI remains the model and
tool integration layer; LangGraph4j owns the conversational control flow.

This is a design for the current target of 100 total users with occasional
bursts and a normal-load p95 response-time objective of 30 seconds. It starts
with one active application replica and should remain safe to scale out later.

## Current project context

The service currently uses Spring Boot 4.1.0, Spring AI 2.0.1, and Spring
Modulith 2.1.1. The application already has a JDBC-backed Modulith event
publication registry, publishes `InboundMessageAccepted` from the transactional
inbound message path, and handles it with an `@ApplicationModuleListener` in
`ConversationWorkflow`.

The current event includes message text and participant data. Modulith persists
serialized event publications, so future inbound event payloads should contain
identifiers only. The canonical message body remains in PostgreSQL and is
loaded by the listener using the receipt identifier.

Conversation messages and the durable memory projection are PostgreSQL-backed.
Redis is an optional, fail-open cache for the versioned memory projection. The
deployment template enables it with a 24-hour cache TTL. The database remains
the source of truth. The current Spring AI model client also has a configured
client retry policy; implementation must consolidate effective retries rather
than accidentally multiplying client- and framework-level retries.

## Architecture

### Event intake and durable dispatch

1. Validate the channel webhook and deduplicate it using the provider message
   identity.
2. In one database transaction, persist the inbound message and publish an
   identifier-only `InboundMessageAccepted` event containing tenant ID,
   conversation ID, receipt ID, channel, and provider message ID as needed.
3. Return the channel acknowledgement only after the transaction commits.
4. Let Spring Modulith's JDBC event publication registry durably track the
   publication for the asynchronous listener. Keep this as the queueing
   mechanism for the current scale; do not add a second queue or broker now.
5. Configure explicit stale-publication detection, bounded redelivery, and
   operational replay for failed publications. Preserve at-least-once delivery
   semantics and make listener side effects idempotent. Do not assume that
   adding the registry alone creates an unlimited automatic retry policy.

No database reset is part of this design. Event schema/payload evolution must
allow pending publications to be handled safely during deployment.

### Ordered turn worker

The Modulith listener claims a PostgreSQL-backed lease for the conversation
before beginning a turn. Only one turn per conversation may execute at a time;
different conversations may execute concurrently within configured limits.
The lease has an owner and expiry so another worker can recover work after a
process failure. Never hold a database transaction open during model or MCP
network calls.

Use a bounded worker/admission policy, provider-specific concurrency limits,
and observable queue age/depth. Initially these limits apply to the single app
replica. If additional replicas are introduced, per-conversation leases remain
database-coordinated and provider-wide limits must be coordinated across
replicas rather than multiplied unintentionally.

### Spring and LangGraph4j boundaries

- Spring controllers and Modulith listeners call a Spring-owned conversation
  application port; they do not depend directly on LangGraph4j APIs.
- LangGraph4j implements the conversation agent and owns turn state transitions,
  tool-selection loops, and checkpoint/resume behavior.
- Spring AI adapters own provider clients and tool integrations, including the
  approved Ford MCP/RAG operations.
- Spring owns tenant authorization, inbound/outbound persistence, work leases,
  provider allow-lists and limits, retry policy, caching, and observability.

Use one stable, tenant-scoped graph thread per conversation. Each inbound
receipt is a distinct turn within that thread. Checkpoints hold compact workflow
state, not a second transcript or a second canonical memory store. Expire graph
checkpoints after 90 days of inactivity along with the working-memory
projection.

### Model and tool policy

The graph is model-led rather than a rigid deterministic intent-to-response
router. The model may answer directly or request an allowed tool. Deterministic
logic remains for security and operational guardrails: tenant isolation,
tool/provider allow-lists, deadlines, bounded loops, and grounded handling of
volatile facts.

- The primary model is attempted first. During each processing window, if it
  fails transiently or is rate-limited, make at most one further model call to
  an explicitly approved fallback provider. Thus there are at most two model
  calls per window, including fallback. Never silently switch to an unapproved
  provider.
- If both approved model calls fail transiently, keep the turn queued and
  resubmit it in bounded backoff windows. Each new window gets a fresh two-call
  budget. Use provider `Retry-After` where available, otherwise backoff with
  jitter. Stop automatic resubmission when the turn reaches 15 minutes of queue
  age. Mark expired turns for operational handling; do not silently discard
  them or retry them forever. Persist the first-queued time and retry-window
  state so redelivery and process restarts do not reset the age limit.
- Retry only transient failures. Bound each processing window's call time and
  keep any scheduled backoff within the 15-minute queue-age limit. Do not retry
  permanent authorization, validation, or malformed-request errors.
- A read-only MCP/RAG operation may be attempted twice total (initial attempt
  plus one retry) for transient errors. Bound the graph to at most three
  tool-call rounds per turn and to the overall response budget.
- If live inventory, pricing, or promotions remain unavailable after retries,
  do not invent or imply current facts. Tell the customer the information could
  not be verified.
- Human handoff remains disabled for now. Lead intent can be represented in
  graph state for future use, but it must not redirect the current bot flow.

Use Spring Framework's resilience facilities at the external-call boundary.
Use a Spring `RetryTemplate`/provider-selection policy for model calls so the
initial call selects the primary and the single retry selects one approved
fallback; this gives exactly two model calls per processing window rather than
retrying the primary and then also calling the fallback. Use Spring's
`@Retryable(maxRetries = 1)` or equivalent `RetryTemplate` policy for an MCP
operation, giving one initial call plus one retry. Avoid retrying the entire
graph turn, which could repeat model costs or outbound side effects. Disable or
align lower-level provider-client retries so the effective call budget is not
multiplied. In Spring Framework 7, `@Retryable(maxRetries = 1)` means one
initial invocation plus one retry; verify this against the resolved dependency
version when implementing.

### Memory and data retention

Keep the data stores and lifetimes distinct:

| Data | Store | Lifetime |
|---|---|---|
| Full conversation transcript | PostgreSQL | One year |
| Working-memory projection | PostgreSQL | Expire/reset after 90 days of inactivity |
| Hot working-memory cache | Redis | 24-hour TTL |
| LangGraph conversation checkpoint | PostgreSQL checkpoint store | Expire/reset after 90 days of inactivity |

The next inbound message after 90 inactive days starts a fresh working-memory
period. The assistant must not reconstruct expired working memory from the
retained transcript. The transcript stays available for its retention period,
but is not injected into the new assistant context. Expiration must invalidate
any Redis entry so an old cached projection cannot resurrect expired state.

Build prompt memory from a token-budgeted recent-turn window, a progressively
compacted summary, and selected durable facts with provenance. PostgreSQL is
authoritative; Redis stays fail-open and uses the existing version-aware cache
boundary. The 24-hour Redis TTL is not a substitute for the 90-day PostgreSQL
expiry.

Use Spring's cache abstraction/`@Cacheable` for safe, tenant-scoped, reusable
lookups with provider-configured TTLs. Do not cache generated replies,
conversation turns, or volatile offers as if they were stable. Retain the
version-aware conversation-memory cache adapter rather than replacing its
consistency behavior with annotations alone. Ensure cache calls cross Spring
proxy boundaries and use tenant-safe keys.

### Outbound delivery and idempotency

Persist the completed assistant turn and outbound delivery request before
calling the channel provider, using the existing outbox path. A replayed event
must not create a second assistant turn or duplicate logical outbound request;
use the inbound receipt/turn identity as the idempotency key. Record provider
delivery identifiers and final delivery status for reconciliation.

## Observability and verification

Propagate trace/correlation identifiers from inbound receipt through event
publication, graph checkpoint, model attempt/fallback, MCP/RAG call, memory
load, and outbound delivery. Do not log raw message text, prompts, tokens, or
provider credentials. Emit metrics for:

- inbound publication age/depth, worker wait, lease recovery, and failed/replayed
  publications;
- end-to-end turn latency and p95 against the 30-second normal-load objective;
- per-provider attempts, fallback usage, throttles, latency, and failure class;
- MCP/RAG latency, retries, timeout/error rates, and graph tool-round count;
- Redis hit/miss/failure rate, PostgreSQL memory expiry, and outbound delivery
  status.

Before rollout, verify duplicate webhook idempotency, crash/restart recovery,
strict ordering within a conversation, bounded concurrency across conversations,
approved-provider-only fallback, two-call-per-window model budget, 15-minute
queue-age cap, two-attempt tool policy, three-round graph bound, and safe
behavior when live data is unavailable.
Exercise retention boundaries: Redis 24-hour expiry, working-memory/checkpoint
reset after 90 inactive days without transcript rehydration, and transcript
retention for one year. Run a representative 100-user burst/load test and check
the p95 objective, queue growth, and recovery behavior under provider throttling
and MCP outages.

## Alternatives considered

1. **In-process async listeners only:** least operational work, but weaker
   recovery and burst control if a process stops or the provider is overloaded.
2. **Spring Modulith JDBC publication registry (selected):** reuses the existing
   transactional event path and PostgreSQL, with explicit recovery,
   idempotency, ordering, and admission controls added around the listener.
3. **External broker now:** useful for higher sustained throughput, multiple
   independent consumers, or larger deployments, but adds infrastructure and
   operational complexity not justified by the current 100-user burst target.

## Out of scope

- Adding Kafka, RabbitMQ, or another separate queue now.
- Enabling human handoff or changing the current handoff environment flag.
- Resetting or rebuilding production databases.
- Replacing the provider or Ford MCP/RAG system.
- Rewriting all schemas; changes should be targeted to event payloads, turn
  lease/recovery, graph checkpoint lifecycle, and retention policy.
