# Adaptive Ford Lead Journey Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and continue a lead's non-linear Ford sales journey across messages while answering the current question first and avoiding repeated qualification.

**Architecture:** Keep conversation memory, CRM lifecycle, and sales-journey state separate. Persist a tenant-scoped `lead_journey` snapshot by conversation, optionally link a CRM lead, pass a safe snapshot to the existing Spring AI tool-enabled completion, and return a typed reply plus optional journey proposal from the same call. Validate and persist the proposal only with the accepted, idempotent conversation turn.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1 `ChatClient`, PostgreSQL, Liquibase, `JdbcClient`, Gradle, JUnit 5, AssertJ, Testcontainers PostgreSQL, existing model-agnostic chat evaluator.

## Global Constraints

- Keep Spring AI at the repository BOM version `2.0.1`; do not add LangGraph4j or another model/provider/RAG dependency.
- Keep local Docker on the user's default profile; scope test commands with `env -u DOCKER_HOST` where the shell override points at another socket. Do not start or switch Docker profiles.
- Preserve the default Ford topics: vehicle discovery → promotions/pricing → financing/trade-in → test drive/appointment; topics are optional and interruptible, not a required linear script or completion claim.
- The current inbound message takes priority; use current authorized Ford tool results for volatile facts; do not mark failed provider/tool/action work as successful.
- Scope state by tenant and conversation; an optional CRM lead link must belong to the same tenant/conversation.
- Return the natural-language answer and typed journey proposal from the same model completion/tool loop; do not add a separate model extraction call. Preserve the existing bounded response-quality repair behavior.
- Only facts with an exact evidence quote from the current inbound message can enter the confirmed user-fact snapshot; a visited topic is never proof of a verified offer, approved financing, appraisal, or completed appointment.
- Persist accepted turn, journey proposal, outbound message, and outbox intent in the same transaction; stable assistant message ID derives from the inbound provider message ID.
- Do not log raw messages, contact data, prompts, provider output, tool arguments, or tool results.
- Write a failing test before each production change; run PostgreSQL-backed tests for SQL, Liquibase, locking, and idempotency.

---

## File map

- `ford-ai-service/src/main/java/com/ford/service/leads/domain/LeadJourney*.java`: immutable journey state, topics, fact keys, fact provenance, and typed model proposal.
- `ford-ai-service/src/main/java/com/ford/service/leads/application/LeadJourneyPolicy.java`: allowlisted merge/validation rules for model proposals.
- `ford-ai-service/src/main/java/com/ford/service/leads/application/LeadJourneyService.java` and `LeadJourneyRepository.java`: conversation-scoped load and transactional state update contract.
- `ford-ai-service/src/main/resources/db/changelog/changes/012-lead-journey.yaml` and `db.changelog-master.yaml`: versioned PostgreSQL schema.
- `ford-ai-service/src/main/java/com/ford/service/leads/adapter/out/JdbcLeadJourneyRepository.java`: `JdbcClient` persistence adapter.
- `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantGeneration.java` and `AssistantResponse.java`: final model generation and response transport with optional journey proposal.
- `ford-ai-service/src/main/java/com/ford/service/ai/application/LeadJourneyContextFormatter.java`: safe rendering of journey state for the prompt without identifiers or sensitive data.
- `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantRequest.java`, `AssistantTurn.java`, `application/AiExecutionContext.java`, and `application/SpringAiAssistantService.java`: pass safe journey context and return the proposal without an extra model call.
- `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`: request `AssistantGeneration` via the Spring AI structured-output API; retain authorized tools and response-quality checks.
- `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationTurnRequest.java`, `api/AssistantReply.java`, and `application/ConversationAssistantAdapter.java`: carry state into the AI layer and proposal back to the workflow.
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationTurnCommitService.java`, `ConversationWorkflow.java`, and `ConversationConfiguration.java`: atomically persist idempotent assistant turn + journey update before publishing follow-up events.
- `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java`: allow one bounded journey operation with success/failure/skipped outcomes; never attach message/fact values as tags.
- `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`: teach the assistant to answer first, use journey context as data, select at most one next question, and tolerate jumps/corrections.
- `ford-ai-service/src/test/java/com/ford/service/leads/LeadJourneyPolicyTest.java` and AI/conversation unit tests: domain and interaction behavior.
- `ford-ai-service/src/integrationTest/java/com/ford/service/leads/LeadJourneyRepositoryIntegrationTest.java` and `conversations/LeadJourneyTurnIntegrationTest.java`: PostgreSQL persistence and turn transaction behavior.
- `scripts/ai/lead-journey-eval.test.mjs` plus fixture files under `scripts/ai/fixtures/lead-journey/`: es-MX multi-turn acceptance scenarios.

## Task 1: Define safe journey state and merge policy

**Interfaces:**

```java
public record LeadJourneySnapshot(
        UUID tenantId,
        UUID conversationId,
        UUID leadId,
        LeadJourneyTopic currentTopic,
        Set<LeadJourneyTopic> visitedTopics,
        Set<LeadJourneyTopic> pausedTopics,
        Map<LeadJourneyFactKey, LeadJourneyFact> facts,
        String pendingQuestionKey,
        int pendingQuestionAttempts,
        String lastProcessedMessageId,
        long version,
        Instant updatedAt) { }

public record LeadJourneyProposal(
        LeadJourneyTopic currentTopic,
        List<ProposedLeadJourneyFact> facts,
        String pendingQuestionKey) { }

public record ProposedLeadJourneyFact(LeadJourneyFactKey key, String value, String exactUserEvidence) { }

LeadJourneySnapshot LeadJourneyPolicy.apply(
        LeadJourneySnapshot current,
        LeadJourneyProposal proposal,
        String currentInboundMessage,
        boolean sensitiveDataDetected,
        String providerMessageId,
        Instant now);
```

The topic enum is `VEHICLE_DISCOVERY`, `PROMOTIONS_PRICING`, `FINANCING_TRADE_IN`, or `TEST_DRIVE_APPOINTMENT`. The fact-key enum is `VEHICLE_MODEL`, `VEHICLE_USE`, `NEW_OR_USED`, `BUDGET_RANGE`, `PURCHASE_TIMING`, `FINANCE_INTEREST`, `TRADE_IN_INTEREST`, or `DEALERSHIP_LOCATION`. The policy rejects unknown fact keys, blank/oversized values, evidence not present in the exact current inbound text, sensitive data, and long digit runs/contact/account patterns. If the existing decision marks sensitive data `CONFIRMED`, capture no facts from that turn. A topic switch pauses the previous topic and marks the new topic visited; a model proposal never marks an external action complete. An explicit correction replaces only that fact and preserves unrelated journey state. If the proposal is absent/invalid, retain current state.

**Files:** create the domain records/enums (`LeadJourneySnapshot`, `LeadJourneyTopic`, `LeadJourneyFactKey`, `LeadJourneyFact`, `LeadJourneyProposal`, `ProposedLeadJourneyFact`) and `LeadJourneyPolicy.java`; create `src/test/java/com/ford/service/leads/LeadJourneyPolicyTest.java`.

- [ ] **Step 1: Write failing policy tests** for valid exact-quote fact capture, missing/altered quote rejection, confirmed-sensitive-turn rejection, long digit/contact pattern rejection, unknown fact key rejection, topic switch pausing and visiting, explicit correction preserving other facts, and absent/invalid proposal leaving journey fields unchanged.
- [ ] **Step 2: Run the focused test** with `env -u DOCKER_HOST ./gradlew test --tests com.ford.service.leads.LeadJourneyPolicyTest`; expected: compile/test failure because journey types and policy do not exist.
- [ ] **Step 3: Add the minimal immutable types and `LeadJourneyPolicy.apply(...)`** matching the interface above; use enum allowlists for topics and fact keys, and bound string lengths.
- [ ] **Step 4: Re-run the focused test** with `env -u DOCKER_HOST ./gradlew test --tests com.ford.service.leads.LeadJourneyPolicyTest`; expected: all `LeadJourneyPolicyTest` cases pass, with existing tests unchanged.
- [ ] **Step 5: Commit** with `feat(leads): add validated lead journey state`.

## Task 2: Persist conversation-scoped state in PostgreSQL

**Interfaces:**

```java
public interface LeadJourneyRepository {
    LeadJourneySnapshot getOrCreate(UUID tenantId, UUID conversationId);
    boolean update(UUID tenantId, UUID conversationId, long expectedVersion,
                   LeadJourneySnapshot snapshot, String providerMessageId);
}

public final class LeadJourneyService {
    public LeadJourneyService(LeadJourneyRepository repository, LeadJourneyPolicy policy);
    public LeadJourneySnapshot load(UUID tenantId, UUID conversationId);
    public boolean apply(UUID tenantId, UUID conversationId, LeadJourneySnapshot expectedSnapshot,
                         LeadJourneyProposal proposal, String currentMessage, boolean sensitiveDataDetected,
                         String providerMessageId);
}
```

Create one row per conversation in `lead_journey`: tenant/conversation identifiers, nullable CRM `lead_id`, relational `current_topic`, `visited_topics` and `paused_topics` JSONB arrays, `facts` JSONB object, pending question key/attempt count, last processed provider message ID, optimistic version, and update time. Add FK to conversation and nullable FK to lead; every query also filters by tenant. `getOrCreate` is safe under concurrent first messages and links a lead only if a same-tenant lead already exists for the conversation. `update` must match tenant, conversation, expected version, and provider message identity.

**Files:** create `LeadJourneyRepository.java`, `LeadJourneyService.java`, `JdbcLeadJourneyRepository.java`, `012-lead-journey.yaml`, `LeadJourneyRepositoryIntegrationTest.java`; update `db.changelog-master.yaml`; update the leads configuration only if a bean is required by existing wiring conventions.

- [ ] **Step 1: Write failing PostgreSQL integration tests** for lazy creation without a CRM lead, tenant-scoped reads, optional lead linkage, JSONB round trip/provenance, optimistic version conflict, and duplicate provider-message update rejection.
- [ ] **Step 2: Run `env -u DOCKER_HOST ./gradlew integrationTest --tests com.ford.service.leads.LeadJourneyRepositoryIntegrationTest`**; expected: tests fail because the table/repository do not exist.
- [ ] **Step 3: Add Liquibase change set `012-lead-journey` and register it** after `011-lead-commands`; implement `JdbcLeadJourneyRepository` with `JdbcClient`, PostgreSQL JSONB casts, tenant filters, and optimistic updates; wire `LeadJourneyService` with injected repository and policy.
- [ ] **Step 4: Re-run with `env -u DOCKER_HOST ./gradlew integrationTest --tests com.ford.service.leads.LeadJourneyRepositoryIntegrationTest`**; expected: Liquibase applies on Testcontainers PostgreSQL and every persistence/concurrency assertion passes.
- [ ] **Step 5: Commit** with `feat(leads): persist tenant-scoped journey snapshots`.

## Task 3: Pass journey context through the existing model/tool call

**Interfaces:**

```java
public record AssistantGeneration(String answer, LeadJourneyProposal journeyProposal) { }

public record AssistantResponse(String content, boolean handoffRequired, String provider,
                                List<String> sourceDocumentIds,
                                LeadJourneyProposal journeyProposal) { }
```

Add `LeadJourneyContextFormatter` containing only current topic, visited/paused topics, allowlisted facts, and pending question; exclude tenant, conversation, lead, and provider IDs. `ConversationTurnRequest` carries the snapshot to `ConversationAssistantAdapter`; `AiExecutionContext` and `AssistantRequest` carry the formatted context to `SpringAiChatCompletionAdapter`. The adapter calls `.call().entity(AssistantGeneration.class)` in the same `ChatClient` request that has authorized tools, evaluates `answer` with `AssistantResponseQualityPolicy`, and maps the proposal into `AssistantResponse`. Keep compatibility constructors on changed public records so existing callers/tests continue to compile. A structured conversion failure returns through existing provider recovery and yields no proposal.

**Files:** create `ai/api/AssistantGeneration.java`; modify `AssistantRequest.java`, `AssistantResponse.java`, `AssistantTurn.java`, `AiExecutionContext.java`, `SpringAiAssistantService.java`, `SpringAiChatCompletionAdapter.java`, `ConversationTurnRequest.java`, `AssistantReply.java`, and `ConversationAssistantAdapter.java`; add/extend adapter and mapping tests.

- [ ] **Step 1: Write failing unit tests** for safe prompt rendering (no IDs/PII), current-message priority, a structured answer+proposal mapped from one model call, tool callbacks still available during the call, quality repair retaining structured output, and malformed structured output producing no proposal.
- [ ] **Step 2: Run focused unit tests** with `env -u DOCKER_HOST ./gradlew test --tests '*SpringAiChatCompletionAdapterTest' --tests '*ConversationAssistantAdapterTest'`; expected: compilation/test failures for missing journey context and generation fields.
- [ ] **Step 3: Add typed `AssistantGeneration` and thread the snapshot/proposal through existing records**; switch the Spring AI adapter to `.call().entity(AssistantGeneration.class)` without registering a new advisor, provider, or extraction call; preserve response-quality validation against `answer`.
- [ ] **Step 4: Re-run with `env -u DOCKER_HOST ./gradlew test --tests '*SpringAiChatCompletionAdapterTest' --tests '*ConversationAssistantAdapterTest'`**; expected: the existing tool callback set is used and one ChatClient request returns both reply and proposal without a separate state-extraction call.
- [ ] **Step 5: Commit** with `feat(ai): include lead journey in the assistant turn`.

## Task 4: Commit the assistant turn and journey update atomically/idempotently

**Interfaces:**

```java
boolean ConversationRepository.completeBotTurn(
        UUID conversationId, long expectedVersion,
        String inboundProviderMessageId, ConversationTurn turn);

boolean ConversationTurnCommitService.complete(
        UUID tenantId, UUID conversationId, long expectedConversationVersion,
        LeadJourneySnapshot expectedJourney, LeadJourneyProposal proposal,
        String inboundProviderMessageId, String currentMessage, boolean sensitiveDataDetected,
        ConversationTurn turn);
```

Derive a stable assistant provider message ID from conversation ID + inbound provider message ID. A duplicate commit returns without inserting another assistant transcript row, journey update, or outbox event. A new commit runs `ConversationRepository.completeBotTurn` and `LeadJourneyService.apply` in one explicit transaction; the model/MCP call remains outside that transaction. Pass `sensitiveDataDetected=true` only when `reply.decision().sensitiveDataLevel() == SensitiveDataLevel.CONFIRMED`; then suppress fact capture while still allowing a safe topic transition. A null/invalid proposal does not update the journey. Publish memory refresh/outbound events only after the transaction succeeds. Resolve any module boundary with the existing Spring Modulith named interfaces rather than moving unrelated modules.

**Files:** create `ConversationTurnCommitService.java`; modify `ConversationRepository.java`, `JdbcConversationRepository.java`, `ConversationWorkflow.java`, `ConversationConfiguration.java`, and `DefaultAiObservationService.java`; add unit tests and `conversations/LeadJourneyTurnIntegrationTest.java`.

- [ ] **Step 1: Write failing tests** proving duplicate inbound provider IDs yield one assistant transcript/outbox row, a stale conversation or journey version yields no partial write, provider/model failure does not update journey state, and a journey persistence exception rolls back the assistant transcript/outbox insert.
- [ ] **Step 2: Run focused tests** with `env -u DOCKER_HOST ./gradlew test --tests '*ConversationWorkflowTest' --tests '*JdbcConversationRepositoryTest'` and `env -u DOCKER_HOST ./gradlew integrationTest --tests com.ford.service.conversations.LeadJourneyTurnIntegrationTest`; expected: duplicate or rollback cases expose missing atomic idempotency.
- [ ] **Step 3: Add the transactional commit service**; derive the stable assistant provider ID with `UUID.nameUUIDFromBytes((conversationId + ":" + inboundProviderMessageId).getBytes(UTF_8))`, update `JdbcConversationRepository` to check/insert that stable ID, and apply the journey proposal only when the conversation turn wins its version check.
- [ ] **Step 4: Emit a bounded `ford.conversation.lead_journey` observation** tagged only with `outcome=success|failure|skipped`, then re-run focused and PostgreSQL tests; expected: one accepted inbound ID creates at most one assistant message, journey update, and outbox event; injected persistence failure leaves none of those partial writes; no customer data appears in telemetry.
- [ ] **Step 5: Commit** with `feat(conversations): persist journey with idempotent turns`.

## Task 5: Tune the prompt and prove the complete sales journey

**Files:** modify `src/main/resources/prompts/ford-uruapan-system.md`; create `scripts/ai/lead-journey-eval.test.mjs` and fixtures in `scripts/ai/fixtures/lead-journey/`; extend the closest existing Java integration test only when it improves deterministic cross-layer coverage.

- [ ] **Step 1: Add failing evaluator scenarios** for a direct promotion question before discovery, a later finance question after a vehicle discussion, direct jump to test drive, correction of a preferred model, short contextual reply, declined question, unavailable promotion source, process-restart state recovery, and no repeated question after an already confirmed fact.
- [ ] **Step 2: Run `node --test scripts/ai/lead-journey-eval.test.mjs`**; expected: scenarios fail against the current prompt/assistant behavior or the evaluation harness rejects missing journey output.
- [ ] **Step 3: Update the system prompt** to use journey state only as context, answer the latest request first, not force stage order, use at most one useful question, avoid re-asking known facts, and never invent current offers or mark unconfirmed actions complete.
- [ ] **Step 4: Run `node --test scripts/ai/lead-journey-eval.test.mjs`**; expected: all deterministic fixtures pass and response quality checks show no internal state/tool payload leakage.
- [ ] **Step 5: Run final verification** with `env -u DOCKER_HOST ./gradlew check` and `node --test scripts/ai/lead-journey-eval.test.mjs`; expected: `BUILD SUCCESSFUL` and zero failed evaluation tests. Inspect `git diff --check` and module verification output before any deployment decision.
- [ ] **Step 6: Commit** with `test(ai): cover adaptive Ford lead journey` (include prompt/evaluator changes in this focused commit).

## Dependency order and scope review

```text
Task 1 (domain rules)
   ↓
Task 2 (PostgreSQL persistence)
   ↓
Task 3 (single-call model context/output) ──┐
   ↓                                       ↓
Task 4 (atomic conversation + journey commit)
   ↓
Task 5 (prompt, end-to-end evaluations, full verification)
```

This is one cohesive backend feature: none of the persistence, model contract, or turn commit slices is useful on its own to the customer. No new deployment, secret propagation, cross-channel lead identity, automatic follow-up, appointment write integration, or human-handoff activation is included. Deployment remains separately gated on production provider recovery and a successful live chat verification.
