# Durable Conversation Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist tenant-scoped conversation memory and inject bounded session context into every AI model/provider call while preserving webhook acknowledgement and idempotency.

**Architecture:** PostgreSQL remains the source of truth for the normalized transcript and versioned session projection. The existing pgvector/Spring AI infrastructure provides optional semantic fact retrieval behind a narrow port. The conversation workflow loads one provider-neutral context shared by all agents and models; Redis and RabbitMQ remain optional/future infrastructure.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Modulith events, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL/pgvector, Spring AI 2.x, JUnit 5, Mockito, Spring test slices, H2 only for existing unit/slice support.

## Global Constraints

- Use PostgreSQL as canonical memory storage; Redis is never the source of truth.
- Session identity is `tenant_id + channel + provider_account_id + participant_id`.
- Preserve provider webhook idempotency and the existing 202 acknowledgement path.
- Memory/provider/vector/cache failures must not invalidate a valid webhook acknowledgement.
- Human handoff remains controlled by `FORD_HUMAN_HANDOFF_ENABLED`.
- Semantic memory is optional and disabled by default unless explicitly enabled.
- Do not add RabbitMQ dependencies or compose services in this implementation.
- New SQL should use `JdbcClient` when supported by the project’s resolved Spring version.
- Write a failing unit or Spring slice test before each production-code change.
- Do not add or run integration tests for this feature; use `./gradlew test`, not `./gradlew check`.
- Never log API keys, provider access tokens, raw webhook bodies, or full sensitive user content.

---

## File and responsibility map

Create or modify only these responsibilities:

```text
ford-ai-service/src/main/java/com/ford/service/conversations/domain/
  ConversationMemoryKey.java             identity value object
  ConversationMemoryContext.java         prompt-ready memory context
  MemoryFact.java                        bounded durable fact
  MemoryProjectionUpdate.java            summary/fact update command
  MemoryQuery.java                       bounded memory read query
  MemoryUpdateResult.java                optimistic-write result

ford-ai-service/src/main/java/com/ford/service/conversations/application/
  ConversationMemoryPort.java            application memory boundary
  ConversationMemorySemanticPort.java    optional semantic-memory boundary
  NoOpConversationMemorySemanticPort.java disabled semantic-memory adapter
  ConversationMemoryService.java         failure-tolerant context policy
  MemoryProperties.java                  ford.memory configuration
  ConversationConfiguration.java         memory bean wiring

ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/
  JdbcConversationMemoryRepository.java  relational transcript/projection adapter
  PgVectorConversationMemoryAdapter.java optional semantic-memory adapter

ford-ai-service/src/main/java/com/ford/service/ai/
  api/AssistantRequest.java               provider-neutral memory input
  adapter/out/springai/SpringAiChatCompletionAdapter.java prompt assembly

ford-ai-service/src/main/java/com/ford/service/channels/
  domain/InboundMessage.java              provider-account identity
  adapter/in/meta/AbstractMetaChannelAdapter.java deterministic session ID
  adapter/out/JdbcInboundMessagePersistenceAdapter.java normalized transcript write

ford-ai-service/src/main/resources/
  db/changelog/changes/007-conversation-memory.yaml schema and indexes
  db/changelog/db.changelog-master.yaml migration include
  application.yml memory defaults and environment bindings

ford-ai-service/src/test/java/com/ford/service/
  conversations/ConversationMemoryServiceTest.java
  conversations/JdbcConversationMemoryRepositoryTest.java
  conversations/ConversationIdentityTest.java
  ai/SpringAiChatCompletionAdapterTest.java
  channels/MetaChannelIdentityTest.java
```

---

### Task 1: Add identity and memory domain contracts

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/ConversationMemoryKey.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/ConversationMemoryContext.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/MemoryFact.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/MemoryProjectionUpdate.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/MemoryQuery.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/MemoryUpdateResult.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/MemoryProperties.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationIdentityTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java`

**Interfaces:**

```java
public record ConversationMemoryKey(
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String participantId,
        UUID conversationId) {}

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

- [ ] **Step 1: Write the failing tests**

  Test that a memory key retains all five identity values, rejects blank
  provider/participant values, and that a service returns an empty context when
  the port throws a runtime exception.

  Use a fake `ConversationMemoryPort` in the service test. The fake exposes
  `RuntimeException loadFailure` and records the received `MemoryQuery`; it must
  not call JDBC, Redis, pgvector, or a model.

- [ ] **Step 2: Run tests to verify failure**

  Run:

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.conversations.ConversationIdentityTest' --tests 'com.ford.service.conversations.ConversationMemoryServiceTest'
  ```

  Expected: compilation failure because the new value objects and service do not
  exist.

- [ ] **Step 3: Implement the minimum contracts**

  Use Java records with compact constructors for non-null IDs and non-blank
  provider/participant values. Copy lists with `List.copyOf`. Define
  `MemoryProperties` with prefix `ford.memory` and these defaults:

  ```java
  enabled=true;
  recentMessageLimit=20;
  summaryMaxChars=4000;
  factLimit=12;
  factMaxChars=500;
  semanticEnabled=false;
  semanticLimit=5;
  cacheEnabled=false;
  ```

  Add `ConversationMemoryService` with constructor injection of
  `ConversationMemoryPort` and `MemoryProperties`; `load` catches memory-store
  failures, logs only IDs/version-safe metadata, and returns an empty context
  when `ford.memory.enabled=false` or the read fails.

- [ ] **Step 4: Run tests to verify pass**

  Run the same focused Gradle command. Expected: PASS with zero skipped tests.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/java/com/ford/service/conversations/domain ford-ai-service/src/main/java/com/ford/service/conversations/application ford-ai-service/src/test/java/com/ford/service/conversations/ConversationIdentityTest.java ford-ai-service/src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java
  git commit -m "feat(memory): add conversation memory contracts"
  ```

---

### Task 2: Fix provider-account session identity and persist the normalized inbound transcript

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/domain/InboundMessage.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/AbstractMetaChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/JdbcInboundMessagePersistenceAdapter.java`
- Create: `ford-ai-service/src/main/resources/db/changelog/changes/007-conversation-memory.yaml`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaChannelIdentityTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/JdbcInboundMessagePersistenceAdapterTest.java`

**Interfaces:**

`InboundMessage` gains `String providerAccountId` immediately after `channel`.
The Meta adapter extracts the account ID with its existing `providerAccountId`
method and generates the deterministic conversation ID from:

```java
tenantId + ":" + channel + ":" + providerAccountId + ":" + participantId
```

The persistence adapter writes the same inbound event to `channel_message` and
`conversation_message` in one transaction, using `ON CONFLICT
(conversation_id, provider_message_id) DO NOTHING` for the normalized row.

- [ ] **Step 1: Write failing tests**

  Add a Meta adapter test with the same participant on two different WhatsApp
  phone-number IDs and assert different conversation IDs. Add a persistence test
  that verifies the normalized insert contains `INBOUND`, the provider message
  ID, content, conversation ID, and received timestamp, and that a duplicate is
  ignored by the conflict clause.

- [ ] **Step 2: Run tests to verify failure**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.channels.MetaChannelIdentityTest' --tests 'com.ford.service.channels.JdbcInboundMessagePersistenceAdapterTest'
  ```

  Expected: compilation/assertion failure because the provider account is not
  part of `InboundMessage` and normalized persistence is absent.

- [ ] **Step 3: Implement identity and persistence**

  Add `providerAccountId` to the record and all constructor call sites. Keep
  `provider_conversation_id` equal to the participant ID for outbound Meta
  delivery, but add `provider_account_id` and `participant_id` to `conversation`.
  Update conversation creation to use the four-part identity and keep the
  existing participant value available to outbound delivery.

  The Liquibase change set must:

  1. add nullable `provider_account_id` and `participant_id` columns;
  2. backfill `participant_id` from `provider_conversation_id`;
  3. backfill `provider_account_id` from the newest matching
     `channel_message -> channel_binding` row, or `legacy` when no binding exists;
  4. set both columns not null;
  5. drop the old `(tenant_id, channel, provider_conversation_id)` uniqueness
     constraint if present;
  6. create unique `(tenant_id, channel, provider_account_id, participant_id)`;
  7. create `conversation_memory` with a one-to-one foreign key to
     `conversation`, tenant/account/participant columns, `summary`, `facts_json`,
     `memory_version`, `source_message_id`, and `updated_at`;
  8. create `conversation_memory_item` with tenant/conversation filters and a
     dimension-flexible `vector` embedding so local and cloud embedding models
     can be selected independently;
  9. create tenant/conversation/time indexes.

  Add the migration include after `006-meta-webhook-routing.yaml`. Annotate
  `JdbcInboundMessagePersistenceAdapter.persist(message, receiptId)` with
  `@Transactional`, insert the normalized inbound row after the audit row, and
  use the provider-account value from `InboundMessage` for the conversation
  upsert.

- [ ] **Step 4: Run tests to verify pass**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.channels.MetaChannelIdentityTest' --tests 'com.ford.service.channels.JdbcInboundMessagePersistenceAdapterTest'
  ```

  Expected: PASS. Do not run `integrationTest` or `check`.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/java/com/ford/service/channels ford-ai-service/src/main/resources/db/changelog ford-ai-service/src/test/java/com/ford/service/channels
  git commit -m "feat(memory): persist provider-scoped inbound transcript"
  ```

---

### Task 3: Implement the PostgreSQL memory repository

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/JdbcConversationMemoryRepositoryTest.java`

**Interfaces:**

`JdbcConversationMemoryRepository` implements `ConversationMemoryPort`.

```java
ConversationMemoryContext load(MemoryQuery query);
void recordInbound(ConversationMemoryKey key, ConversationMessage message);
void recordOutbound(ConversationMemoryKey key, ConversationMessage message);
MemoryUpdateResult updateProjection(
        ConversationMemoryKey key,
        MemoryProjectionUpdate update,
        long expectedVersion);
```

- [ ] **Step 1: Write failing repository tests**

  Use a Spring JDBC slice or injected JDBC test double following the current
  repository test conventions. Cover:

  - newest transcript rows are returned in chronological order;
  - summary and facts are loaded for the exact tenant/conversation key;
  - inbound/outbound inserts are idempotent by provider message ID;
  - stale `memory_version` returns `MemoryUpdateResult(false, currentVersion)`;
  - projection updates truncate summary/facts before SQL execution according to
    `MemoryProperties`.

- [ ] **Step 2: Run tests to verify failure**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.conversations.JdbcConversationMemoryRepositoryTest'
  ```

  Expected: compilation failure because the repository is absent.

- [ ] **Step 3: Implement SQL adapter**

  Implement `load` as three tenant-scoped queries: projection, bounded
  transcript, and facts. Order transcript rows by `received_at asc, id asc`
  after selecting the newest `recentMessageLimit` rows. Deserialize `facts_json`
  through the injected `ObjectMapper`; invalid stored JSON yields an empty fact
  list and a warning with conversation ID only.

  Implement inserts with `ON CONFLICT (conversation_id, provider_message_id)
  DO NOTHING`. Implement projection upsert with:

  ```sql
  update conversation_memory
     set summary = :summary,
         facts_json = cast(:factsJson as jsonb),
         memory_version = memory_version + 1,
         source_message_id = :sourceMessageId,
         updated_at = :updatedAt
   where conversation_id = :conversationId
     and tenant_id = :tenantId
     and memory_version = :expectedVersion
  ```

  Return `false` when the update count is zero. Create the initial projection
  with `insert ... on conflict ... do nothing` before loading/updating it.
  Always include tenant and conversation predicates; never query by
  `conversation_id` alone.

- [ ] **Step 4: Run tests to verify pass**

  Run the focused repository test, then:

  ```bash
  cd ford-ai-service
  ./gradlew test
  ```

  Expected: all unit/slice tests pass and no integration tests are invoked.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java ford-ai-service/src/test/java/com/ford/service/conversations/JdbcConversationMemoryRepositoryTest.java
  git commit -m "feat(memory): add PostgreSQL conversation memory repository"
  ```

---

### Task 4: Inject memory into the workflow and provider-neutral AI prompt

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationTurnRequest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/ConversationSnapshot.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationRepository.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AiExecutionContext.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantRequest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java`

**Interfaces:**

Extend request objects without changing provider routing:

```java
public record ConversationTurnRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        ConversationState state,
        ConversationMemoryContext memory) {}

public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        String conversationState,
        String memoryContext,
        boolean containsSensitiveData) {}
```

- [ ] **Step 1: Write failing tests**

  Extend the Spring AI adapter test to verify the system prompt includes, in
  order, summary, recent transcript, and durable facts before the user message.
  Add a test that a 21-message context with a limit of 20 sends only the newest
  20 messages and does not duplicate the current inbound message.

  Extend the workflow test with a fake memory port and assert it is loaded
  before `ConversationAssistant.respond`, while a memory-port exception still
  allows the workflow to complete the bot turn with an empty context.

- [ ] **Step 2: Run tests to verify failure**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.ai.SpringAiChatCompletionAdapterTest' --tests 'com.ford.service.conversations.ConversationWorkflowTest'
  ```

  Expected: compilation/assertion failure because request records and prompt
  assembly do not contain memory.

- [ ] **Step 3: Implement context propagation**

  Inject `ConversationMemoryService` into `ConversationWorkflow`, build a
  `MemoryQuery` from the snapshot’s tenant/channel/participant/provider account,
  and pass the returned context through `ConversationTurnRequest`,
  `ConversationAssistantAdapter`, `AiExecutionContext`, and `AssistantRequest`.

  Add provider-neutral prompt text to `SpringAiChatCompletionAdapter`:

  ```text
  Responde en español, no inventes datos y usa solo herramientas autorizadas.
  Contexto persistente de la sesión:
  {summary}
  Historial reciente:
  {role}: {content}
  Hechos relevantes:
  - {kind}: {content}
  ```

  Keep the current user message in `.user(request.message())` only once. Escape
  or delimit memory content so it cannot replace system instructions. Keep tools
  and tenant tool context unchanged. Do not include provider tokens, raw JSON,
  or full payloads in the prompt.

- [ ] **Step 4: Run tests to verify pass**

  ```bash
  cd ford-ai-service
  ./gradlew test
  ```

  Expected: all unit/slice tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/java/com/ford/service/conversations ford-ai-service/src/main/java/com/ford/service/ai ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java
  git commit -m "feat(memory): provide durable context to assistant prompts"
  ```

---

### Task 5: Add optional pgvector semantic memory with safe fallback

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemorySemanticPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/PgVectorConversationMemoryAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/PgVectorConversationMemoryAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java`

**Interfaces:**

```java
public interface ConversationMemorySemanticPort {
    List<MemoryFact> search(ConversationMemoryKey key, String query, int limit);
    void upsert(ConversationMemoryKey key, MemoryFact fact);
}
```

- [ ] **Step 1: Write failing tests**

  Test the semantic adapter builds a Spring AI vector search with filters for
  `tenant_id`, `conversation_id`, and `channel`. Test the service returns the
  relational summary/transcript when semantic search throws, and returns facts
  only when `FORD_MEMORY_SEMANTIC_ENABLED=true`.

- [ ] **Step 2: Run tests to verify failure**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.conversations.PgVectorConversationMemoryAdapterTest' --tests 'com.ford.service.conversations.ConversationMemoryServiceTest'
  ```

  Expected: compilation failure because the semantic port and adapter are absent.

- [ ] **Step 3: Implement the optional adapter**

  Inject the configured Spring AI `EmbeddingModel` and the project’s
  `JdbcClient` through the composition root. Keep conversation memory in its
  dedicated `conversation_memory_item` table rather than mixing it with the
  knowledge collection. Store provider-neutral embeddings as pgvector values,
  filter by tenant/conversation/channel, and convert only bounded fact content
  into `MemoryFact`. A no-op adapter is selected when no embedding model is
  available; the service also keeps semantic retrieval opt-in.

  Wire a no-op semantic port when semantic memory is disabled. Catch vector
  client errors in `ConversationMemoryService`, log operation and IDs only, and
  continue with relational context. Do not use the knowledge collection for
  conversation memory.

- [ ] **Step 4: Run tests to verify pass**

  ```bash
  cd ford-ai-service
  ./gradlew test
  ```

  Expected: all unit/slice tests pass with semantic memory disabled by default.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/java/com/ford/service/conversations/application ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/PgVectorConversationMemoryAdapter.java ford-ai-service/src/test/java/com/ford/service/conversations
  git commit -m "feat(memory): add optional pgvector semantic recall"
  ```

---

### Task 6: Add configuration, diagnostics, documentation, and final verification

**Files:**
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/.env.example`
- Modify: `ford-ai-service/.env.prod.example`
- Modify: `ford-ai-service/README.md`
- Modify: `ford-ai-service/docs/operations.md`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/MemoryPropertiesTest.java`

- [ ] **Step 1: Write the failing configuration test**

  Bind a `MemoryProperties` instance with defaults and with environment-style
  overrides for `enabled`, message/fact limits, semantic enabled, semantic
  limit, and cache enabled.

- [ ] **Step 2: Run the test to verify failure**

  ```bash
  cd ford-ai-service
  ./gradlew test --tests 'com.ford.service.conversations.MemoryPropertiesTest'
  ```

  Expected: failure because the Spring properties bean and documented bindings
  are not wired.

- [ ] **Step 3: Add configuration and operator guidance**

  Add these bindings to `application.yml`:

  ```yaml
  ford:
    memory:
      enabled: ${FORD_MEMORY_ENABLED:true}
      recent-message-limit: ${FORD_MEMORY_RECENT_MESSAGE_LIMIT:20}
      summary-max-chars: ${FORD_MEMORY_SUMMARY_MAX_CHARS:4000}
      fact-limit: ${FORD_MEMORY_FACT_LIMIT:12}
      fact-max-chars: ${FORD_MEMORY_FACT_MAX_CHARS:500}
      semantic-enabled: ${FORD_MEMORY_SEMANTIC_ENABLED:false}
      semantic-limit: ${FORD_MEMORY_SEMANTIC_LIMIT:5}
      cache-enabled: ${FORD_MEMORY_CACHE_ENABLED:false}
  ```

  Add non-secret examples to both env example files. Document that PostgreSQL
  stores canonical transcript/summary, semantic memory is opt-in, Redis is only
  an optimization, and `./gradlew test` is the feature verification command.
  Add diagnostics for memory enabled/semantic enabled/limits without logging
  credentials or user content.

- [ ] **Step 4: Run final verification**

  ```bash
  cd ford-ai-service
  ./gradlew test
  git diff --check
  git status --short
  ```

  Expected: all unit/slice tests pass, no whitespace errors, and only planned
  files are modified. Do not run `./gradlew check` because this repository’s
  `check` task invokes the integration-test source set.

- [ ] **Step 5: Commit**

  ```bash
  git add ford-ai-service/src/main/resources/application.yml ford-ai-service/.env.example ford-ai-service/.env.prod.example ford-ai-service/README.md ford-ai-service/docs/operations.md ford-ai-service/src/test/java/com/ford/service/conversations/MemoryPropertiesTest.java
  git commit -m "docs(memory): document durable conversation memory operations"
  ```

---

## Dependency order

```text
Task 1 (contracts/config)
  -> Task 2 (identity + inbound transcript + schema)
  -> Task 3 (PostgreSQL repository)
  -> Task 4 (workflow + prompt integration)
  -> Task 5 (optional pgvector recall)
  -> Task 6 (operator docs + final verification)
```

Task 5 can be stopped after its relational fallback tests if the resolved Spring
AI vector API does not support safe conversation filters without introducing a
new dependency; the bot must still work using summary and recent transcript.

## Definition of done

- [ ] `conversation` identity includes provider account and participant.
- [ ] Every accepted inbound message is present in `channel_message` and the
      normalized `conversation_message` transcript exactly once.
- [ ] PostgreSQL memory summary/version is loaded for every AI turn.
- [ ] Prompt context is bounded and shared across providers/models.
- [ ] pgvector semantic recall is optional, filtered by tenant and conversation,
      and non-blocking on failure.
- [ ] Human handoff behavior is unchanged and still environment-controlled.
- [ ] Unit and slice tests pass; no integration tests were added or executed.
- [ ] Implementation matches the approved design spec.
