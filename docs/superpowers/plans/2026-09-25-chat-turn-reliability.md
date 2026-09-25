# Chat Turn and Memory Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent PostgreSQL memory projection failures and duplicate transcript writes from losing or corrupting accepted chat turns.

**Architecture:** Keep the accepted bot turn, canonical outbound transcript row, and delivery outbox entry in the existing `JdbcConversationRepository.completeBotTurn` transaction. Store memory projections as rebuildable derived state, update them through a separate Spring Modulith listener, and keep the model/MCP call outside the database transaction.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring Modulith 2.1.1, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL 16, JUnit 5, Testcontainers 1.20.6.

## Global Constraints

- `conversation_message` remains the source of truth; `conversation_memory` remains bounded, versioned, rebuildable derived state.
- A derived-memory failure cannot roll back an accepted conversation turn or its outbound-delivery intent.
- Retries remain bounded and idempotent; a successful outbound delivery is never repeated because of a projection failure.
- Transcript, projection, and cache access enforce tenant and conversation boundaries.
- Do not add semantic memory, new database infrastructure, customer-message replay, or human-handoff activation.

---

### Preflight: Classify the live failed turn before changing production state

- [ ] Run `mise run deploy:status` and `mise run deploy:logs`; record app/container health and timestamps around the reported failure.
- [ ] Read the affected conversation and matching `outbox_event` state through the approved DB access path using this read-only query shape:

  ```sql
  select id, event_type, status, attempts, available_at, completed_at, last_error_category
  from outbox_event
  where aggregate_id = :conversation_id
    and event_type = 'OUTBOUND_DELIVERY_REQUESTED'
  order by available_at, id;
  ```

  Do not select payload text, last-error text, or credentials.
- [ ] Classify the turn as committed, pending delivery, delivered, or rolled back. Do not replay or resend it. Expected result: a written recovery decision that cannot create a duplicate reply.

### Task 1: Reproduce the JSONB mismatch against PostgreSQL

**Files:**
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/ConversationMemoryPostgresIntegrationTest.java`
- Reference: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/ConversationRepositoryIntegrationTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/JdbcConversationMemoryRepositoryTest.java`

**Interfaces:**
- Consumes: `JdbcConversationMemoryRepository.updateProjection(ConversationMemoryKey, MemoryProjectionUpdate, long)`.
- Produces: a PostgreSQL-backed regression test that writes and reloads `MemoryFact` values through the existing adapter.

- [ ] **Step 1: Add the failing PostgreSQL round-trip test.** Reuse the `PostgreSQLContainer`, Liquibase setup, and dynamic datasource pattern from `ConversationRepositoryIntegrationTest`. Seed a conversation and `ConversationMemoryKey`, call `updateProjection` with one fact, then assert `applied()` and that `load(...)` returns that exact fact.
- [ ] **Step 2: Run the focused integration test.** From `ford-ai-service`, run `./gradlew integrationTest --tests com.ford.service.conversations.ConversationMemoryPostgresIntegrationTest`. Expected: FAIL with PostgreSQL reporting that `facts_json` is `jsonb` but the supplied expression is `character varying`.
- [ ] **Step 3: Apply the narrow PostgreSQL cast.** In the update SQL in `JdbcConversationMemoryRepository`, change only the assignment to `facts_json = cast(:factsJson as jsonb)`; keep JSON serialization and `JdbcClient` binding unchanged.
- [ ] **Step 4: Run the focused unit and integration tests.** Run `./gradlew test --tests com.ford.service.conversations.JdbcConversationMemoryRepositoryTest` and `./gradlew integrationTest --tests com.ford.service.conversations.ConversationMemoryPostgresIntegrationTest`. Expected: both PASS and the returned fact matches the inserted fact.
- [ ] **Step 5: Commit the adapter and tests.** Commit only these paths with `fix(memory): bind projection facts as postgres jsonb`.

### Task 2: Keep one canonical assistant transcript row

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java`

**Interfaces:**
- Consumes: `ConversationRepository.completeBotTurn(UUID, long, ConversationTurn)`, which already writes the outbound `conversation_message` row and outbox record atomically.
- Produces: exactly one assistant transcript row and one pending outbound outbox event per accepted turn.

- [ ] **Step 1: Add failing assertions before removing the second write.** In `ConversationWorkflowTest`, assert that the memory fake receives no `recordOutbound` call. In `MetaToOutboxVerticalFlowTest`, use production conversation wiring and assert one outbound `conversation_message` row and one outbox entry after processing and replaying the same inbound webhook.
- [ ] **Step 2: Run the focused tests.** Run `./gradlew test --tests com.ford.service.conversations.ConversationWorkflowTest` and `./gradlew integrationTest --tests com.ford.service.e2e.MetaToOutboxVerticalFlowTest`. Expected: the memory unit assertion and production-wiring transcript count expose the redundant write.
- [ ] **Step 3: Remove the redundant transcript write path.** Remove the `memoryService.recordOutbound(...)` call and now-unused `assistantMessageId(...)` helper from `ConversationWorkflow`; then remove `recordOutbound` from `ConversationMemoryService`, `ConversationMemoryPort`, and `JdbcConversationMemoryRepository`, along with fake implementations. Do not change `JdbcConversationRepository.completeBotTurn` or its outbox idempotency behavior.
- [ ] **Step 4: Run both focused tests again.** Use the same commands. Expected: one canonical outbound history row and one outbox item; replaying the inbound webhook adds neither.
- [ ] **Step 5: Commit the focused change.** Commit only the listed implementation/test paths with `fix(memory): avoid duplicate assistant transcript rows`.

### Task 3: Suspend the event transaction during model and MCP calls

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java`

**Interfaces:**
- Consumes: existing `ConversationAssistant.respond(ConversationTurnRequest)` proxy and `AssistantService` port.
- Produces: assistant/provider/tool execution runs with no active JDBC transaction; repository completion still commits turn, transcript, and outbox together.

- [ ] **Step 1: Add a failing transaction-state assertion to the end-to-end test.** Capture `TransactionSynchronizationManager.isActualTransactionActive()` inside the test `AssistantService` and assert it is false after the webhook flow. Keep the existing assertions for persisted outbound content and outbox state.
- [ ] **Step 2: Run the focused integration test.** Run `./gradlew integrationTest --tests com.ford.service.e2e.MetaToOutboxVerticalFlowTest`. Expected: FAIL because `@ApplicationModuleListener` currently opens a transaction around the workflow and the assistant call.
- [ ] **Step 3: Suspend the transaction at the assistant boundary.** Remove `final` from `ConversationAssistantAdapter` so Spring's class-based proxy can advise it, and annotate `respond` with `@Transactional(propagation = Propagation.NOT_SUPPORTED)`. Keep the annotation on this application boundary so the caller's event transaction resumes after model/MCP completion and the existing repository transaction remains atomic.
- [ ] **Step 4: Verify transaction state and persistence.** Rerun the integration test. Expected: the assistant observes no active transaction, and the conversation row, one outbound transcript row, and one outbox entry still commit.
- [ ] **Step 5: Commit the transaction-boundary change.** Commit only these paths with `refactor(conversations): suspend transaction during assistant calls`.

### Task 4: Isolate projection refresh in a retryable module event

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationMemoryProjectionRefreshRequested.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryProjectionListener.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Create: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationMemoryProjectionListenerTest.java`
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/ConversationProjectionIsolationIntegrationTest.java`

**Interfaces:**
- Event: `ConversationMemoryProjectionRefreshRequested(ConversationMemoryKey key, MemoryProjectionUpdate update, long expectedVersion)`.
- Listener: `void on(ConversationMemoryProjectionRefreshRequested event)`.
- Produces: projection refresh executes after the bot-turn transaction, in the listener's own transaction; projection failure leaves the committed turn/outbox intact and the Modulith publication recoverable.

- [ ] **Step 1: Add the failing event-flow tests.** Unit-test that `ConversationWorkflow` publishes the projection event only after `completeBotTurn` accepts the turn. Add a PostgreSQL integration test with a primary failing `ConversationMemoryPort`; assert the completed conversation, one outbound transcript row, and one outbox entry remain committed while the projection publication is incomplete/retryable.
- [ ] **Step 2: Run focused tests.** Run `./gradlew test --tests com.ford.service.conversations.ConversationWorkflowTest --tests com.ford.service.conversations.ConversationMemoryProjectionListenerTest` and `./gradlew integrationTest --tests com.ford.service.e2e.ConversationProjectionIsolationIntegrationTest`. Expected: the integration test demonstrates the current rollback-only failure before the event split.
- [ ] **Step 3: Publish a separate projection event.** Add the bounded event record above; replace the synchronous `memoryService.updateProjection(...)` call with event publication after a successful `completeBotTurn`; add an `@ApplicationModuleListener` that invokes the projection adapter.
- [ ] **Step 4: Preserve retry visibility.** Configure `spring.modulith.events.republish-outstanding-events-on-restart=true` only if the active application config does not already enable it. Do not swallow listener commit failures; allow the Modulith publication registry to retain incomplete work.
- [ ] **Step 5: Run focused unit and PostgreSQL integration tests.** Expected: a simulated projection failure does not undo the accepted turn/outbox, and the publication remains visible for retry.
- [ ] **Step 6: Commit the event split.** Commit only the event, listener, config, and tests with `fix(memory): isolate projection refresh from bot turns`.

## Dependencies and verification

- Task 1 is independent. Tasks 2 and 3 precede Task 4 so the projection listener starts from canonical transcript state and external calls do not hold DB transactions.
- Final gate: from `ford-ai-service`, run `./gradlew test` and `./gradlew integrationTest`. Expected: all unit and PostgreSQL/Testcontainers integration tests pass.
- Spring Modulith's `@ApplicationModuleListener` is an asynchronous transactional event listener with its own transaction; use its publication registry for failed-event visibility and restart recovery: [Spring Modulith event documentation](https://docs.spring.io/spring-modulith/reference/events.html).
- Do not deploy as part of plan execution until the user explicitly asks for deployment; future rollout uses the existing `mise run deploy:app` path.
