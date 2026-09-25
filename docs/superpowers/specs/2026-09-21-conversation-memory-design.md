# Durable Conversation Memory Design

| Field | Detail |
|---|---|
| Date | 2026-09-21 |
| Status | Draft for implementation review |
| Scope | Durable per-session/user memory for the existing Ford AI service |
| Test constraint | Unit and Spring slice tests only; no integration tests |

## 1. Summary

The service already persists webhook events and conversation-related data, but
the AI prompt currently receives only the current inbound message. This design
adds a reusable memory port that loads a bounded recent transcript, a durable
session summary, and optional semantic memories before an agent/model call.

PostgreSQL is the canonical store. The existing relational transcript remains
authoritative for exact history and audit. pgvector is used only to retrieve
durable semantic facts or preferences. Redis is an optional cache/short-lived
coordination layer, never the source of truth. RabbitMQ is not currently part
of this repository and is not required for the first implementation.

The design preserves the existing webhook acknowledgement path: memory read or
projection failures must not prevent a valid Meta webhook from being accepted.

## 2. Existing capabilities and gap

Existing durable data:

- `conversation` identifies a provider conversation and stores state/version.
- `channel_message` stores inbound provider messages and provides webhook
  idempotency through the provider message identifier.
- `conversation_message` stores normalized outbound messages today and is the
  target transcript table for both directions.
- `outbox_event` stores outbound delivery requests.
- `knowledge_document` and `knowledge_chunk` plus Spring AI `VectorStore`
  provide tenant-scoped knowledge retrieval.

Gap:

- inbound messages are not consistently copied to `conversation_message`;
- no application port loads conversation context for an AI request;
- no durable summary/fact projection exists;
- the existing knowledge vector store must not be overloaded as conversation
  memory because knowledge and user/session memory have different retention,
  ownership, and privacy semantics.

## 3. Identity and isolation

The memory/session key is:

```text
tenant_id + channel + provider_account_id + participant_id
```

For WhatsApp this maps to:

- `tenant_id`: owning tenant;
- `channel`: `WHATSAPP`;
- `provider_account_id`: Meta phone-number ID receiving the message;
- `participant_id`: the customer WhatsApp identifier/phone from the provider
  payload.

The internal `conversation.id` remains the stable session identifier. Provider
conversation identifiers are not sufficient as the only identity because the
same participant can use multiple numbers/accounts and multiple tenants can
share a deployment.

Every memory read, write, vector filter, cache key, and projection event must
include the tenant and channel identity. No global cross-tenant memory query is
allowed.

## 4. Recommended data model

### 4.1 Existing transcript tables

`channel_message` remains the immutable provider/audit record. The inbound
processing path must also write a normalized `conversation_message` row with:

```text
conversation_id
direction = INBOUND
provider_message_id
content
raw_payload (optional, subject to existing retention policy)
received_at
```

The unique `(conversation_id, provider_message_id)` constraint makes retries
idempotent. The current outbound persistence path remains responsible for
`OUTBOUND` rows and the outbox request.

### 4.2 New `conversation_memory` projection

Add a versioned relational projection, scoped to one conversation:

```text
conversation_memory
--------------------
conversation_id       UUID primary key references conversation(id)
tenant_id             UUID not null
channel               VARCHAR not null
provider_account_id   VARCHAR not null
participant_id        VARCHAR not null
summary               TEXT not null default ''
facts_json            JSONB not null default '[]'
memory_version        BIGINT not null default 0
source_message_id     UUID nullable
updated_at            TIMESTAMPTZ not null
```

Recommended constraints/indexes:

- unique `(tenant_id, channel, provider_account_id, participant_id)`;
- index `(tenant_id, conversation_id)`;
- optimistic update predicate on `memory_version`;
- no provider access token or secret in this table;
- facts are bounded and explicitly categorized, for example `preference`,
  `lead_context`, or `constraint`.

The projection is not a replacement for the transcript. It is a compact,
rewritable view used to stay within model context limits.

### 4.3 Optional semantic memory table

Add semantic memory only for durable, useful facts rather than every message:

```text
conversation_memory_item
------------------------
id                     UUID primary key
conversation_id        UUID not null references conversation(id)
tenant_id              UUID not null
kind                   VARCHAR not null
content                TEXT not null
embedding              vector(1536) not null
source_message_id      UUID nullable
active                 BOOLEAN not null default true
created_at             TIMESTAMPTZ not null
updated_at             TIMESTAMPTZ not null
```

Use the existing Spring AI vector infrastructure where possible, with metadata
for `tenant_id`, `conversation_id`, `channel`, and `kind`. If the current
vector abstraction cannot enforce conversation filters safely, use a narrow
repository adapter with an explicit tenant/conversation predicate. Do not
reuse the knowledge collection without a hard collection/type distinction.

Semantic memory is optional in the first vertical slice. The relational summary
and bounded transcript are required.

## 5. Ports and adapters

The application layer depends on ports, not JDBC, Spring AI, Redis, or provider
types.

```java
public record ConversationMemoryKey(
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String participantId,
        UUID conversationId) {}

public record ConversationMemoryContext(
        ConversationMemoryKey key,
        String summary,
        List<ConversationMessage> recentMessages,
        List<MemoryFact> durableFacts,
        long memoryVersion) {}

public interface ConversationMemoryPort {
    ConversationMemoryContext load(MemoryQuery query);

    void recordInbound(ConversationMemoryKey key, ConversationMessage message);

    void recordOutbound(ConversationMemoryKey key, ConversationMessage message);

    MemoryUpdateResult updateProjection(
            ConversationMemoryKey key,
            MemoryProjectionUpdate update,
            long expectedVersion);
}
```

The first implementation should be a PostgreSQL adapter using the project’s
current Spring JDBC facilities. New SQL should prefer `JdbcClient` if the
resolved Spring version supports it. Existing repository style may be retained
where changing it would create unrelated churn.

```java
public interface ConversationMemorySemanticPort {
    List<MemoryFact> search(
            ConversationMemoryKey key,
            String query,
            int limit);

    void upsert(MemoryFact fact, ConversationMemoryKey key);
}
```

This port is optional and can be implemented by a pgvector adapter. A no-op
implementation is valid when semantic memory is disabled.

The AI orchestration port receives memory as provider-neutral context:

```java
public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        ConversationState conversationState,
        ConversationMemoryContext memory,
        boolean sensitive) {}
```

No agent or model owns a separate durable history. Agents consume the same
canonical conversation context and may add a bounded projection update after a
turn.

## 6. Request and response flow

```text
Meta webhook
  -> verify signature/binding/idempotency
  -> resolve conversation using tenant/channel/account/participant
  -> persist channel_message
  -> persist normalized INBOUND conversation_message
  -> acknowledge webhook (202)
  -> async workflow loads memory context
  -> assistant receives summary + bounded recent transcript + facts
  -> provider response
  -> persist OUTBOUND conversation_message + outbox delivery request
  -> async projection update with optimistic memory_version
  -> optional semantic fact upsert
```

The current event-publication workflow can remain the asynchronous boundary.
The inbound listener must not make the webhook request wait for an AI provider,
Redis, or vector operation.

Prompt assembly order:

1. fixed safety/system instructions;
2. durable session summary;
3. bounded recent transcript in chronological order;
4. relevant durable facts, if semantic memory is enabled;
5. current message, without duplicating it in the history window.

Limits must be configuration-backed, for example:

- maximum recent messages;
- maximum characters/tokens for summary;
- maximum semantic facts;
- maximum fact length.

The adapter must truncate deterministically and preserve the newest messages.

## 7. Multi-agent and multi-model behavior

All agents/models share the canonical memory context. Routing between models
does not create a new session or a second transcript. The model/provider is
execution metadata, not part of the identity key.

Agent-specific working state should be transient tool context or an explicitly
named projection field. It must not be silently written into the shared summary
as a user fact.

Only a designated memory projection policy may write durable facts. This avoids
several agents racing to rewrite the summary with incompatible interpretations.

The initial implementation may use the existing assistant workflow as the sole
projection writer. Later agents can publish a typed memory-update event without
changing the `ConversationMemoryPort` contract.

RabbitMQ can later carry memory projection jobs if throughput requires it. It is
not added now because there is no RabbitMQ dependency or compose service in the
current repository, and adding it would expand infrastructure scope without
being necessary for durable correctness.

## 8. Consistency, retries, and failure behavior

| Scenario | Required behavior |
|---|---|
| Duplicate provider webhook | Ignore duplicate normalized message and do not call the model twice for the same provider message. |
| Inbound persistence succeeds, memory load fails | Complete the async event with a safe empty context or retry according to existing event policy; never invalidate the webhook acknowledgement. |
| AI provider fails | Keep inbound transcript durable; do not write a fabricated assistant message; leave a diagnosable failed publication/outbox state. |
| Outbound provider fails | Keep outbound intent in `outbox_event`; retry using existing outbox semantics. |
| Concurrent projection updates | Use `memory_version`; reject stale writes and reload/recompute rather than overwriting newer memory. |
| Redis unavailable | Bypass cache and use PostgreSQL; functionality remains correct with higher latency. |
| Vector search unavailable | Use summary and recent transcript; do not fail the conversation turn. |
| Malformed/oversized fact | Reject or truncate at the projection boundary; never store secrets or unbounded payloads. |
| Tenant mismatch | Fail closed and emit a safe diagnostic without exposing memory from another tenant. |

Memory failures should be logged with conversation ID, tenant-safe request ID,
operation, and version; never log access tokens, API keys, full raw webhook
payloads, or full sensitive user content by default.

## 9. Configuration

Suggested environment-backed properties:

```text
FORD_MEMORY_ENABLED=true
FORD_MEMORY_RECENT_MESSAGE_LIMIT=20
FORD_MEMORY_SUMMARY_MAX_CHARS=4000
FORD_MEMORY_FACT_LIMIT=12
FORD_MEMORY_FACT_MAX_CHARS=500
FORD_MEMORY_SEMANTIC_ENABLED=false
FORD_MEMORY_SEMANTIC_LIMIT=5
FORD_MEMORY_CACHE_ENABLED=false
```

The defaults should be safe for development. Production should explicitly set
limits and semantic-memory behavior. No secret belongs in memory configuration.

## 10. Testing strategy

Only unit and Spring slice tests are in scope.

Unit tests:

- session-key construction includes tenant, channel, provider account, and
  participant;
- prompt/context assembly preserves order and limits;
- the current message is not duplicated in the recent window;
- duplicate inbound messages are ignored by the application policy;
- stale memory versions do not overwrite newer versions;
- semantic-memory failures degrade to relational context;
- tenant mismatch fails closed;
- projection policy stores only allowed fact categories and bounded content;
- agents/models receive the same provider-neutral memory context.

Spring slice tests:

- JDBC repository SQL mapping with mocked `JdbcClient`/`NamedParameterJdbcTemplate`
  behavior as appropriate for the existing repository conventions;
- controller/event boundary wiring confirms memory errors do not alter the
  webhook acknowledgement contract;
- configuration properties bind defaults and environment overrides;
- application context exposes enabled/disabled semantic-memory adapters.

No real Docker PostgreSQL, Redis, Meta, OpenRouter, RabbitMQ, or pgvector
integration tests should be added for this change, per the user constraint.

## 11. Implementation file plan

Likely new files:

```text
src/main/resources/db/migration/V__conversation_memory.sql
src/main/java/.../conversations/api/ConversationMemoryKey.java
src/main/java/.../conversations/api/ConversationMemoryContext.java
src/main/java/.../conversations/application/ConversationMemoryPort.java
src/main/java/.../conversations/application/ConversationMemoryService.java
src/main/java/.../conversations/adapter/out/JdbcConversationMemoryRepository.java
src/main/java/.../ai/application/MemoryPromptContext.java
src/test/java/.../conversations/application/ConversationMemoryServiceTest.java
src/test/java/.../ai/application/MemoryPromptContextTest.java
src/test/java/.../conversations/adapter/in/meta/MetaWebhookControllerSliceTest.java
```

Exact package paths must follow the existing module layout. Existing inbound
persistence, conversation workflow, AI request, and Spring AI adapter files
will be updated only after the implementation plan is approved.

## 12. Acceptance criteria

1. A webhook message is durably available as both provider/audit data and a
   normalized inbound conversation message, idempotently.
2. An AI turn receives the same tenant-scoped memory context regardless of the
   selected provider/model.
3. The context includes a bounded recent transcript and durable summary.
4. Optional semantic memory is tenant- and conversation-filtered and can be
   disabled without breaking the bot.
5. Concurrent memory updates cannot silently overwrite newer state.
6. Redis/vector/provider failures do not prevent webhook acknowledgement and do
   not leak memory across tenants.
7. Human handoff remains controlled by the existing feature flag; this design
   does not re-enable handoff.
8. Verification uses unit and slice tests only.

## 13. Open questions intentionally deferred

- exact summary-generation prompt/model and whether it runs synchronously or
  as a later projection job;
- retention/deletion policy for transcript and memory facts;
- whether RabbitMQ will become the production projection transport;
- whether participant identity needs a provider-specific canonicalization table
  for Instagram/Messenger in addition to WhatsApp.

These do not block the first implementation if the existing defaults and
WhatsApp identity mapping are used.
