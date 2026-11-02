# MÍA Conversation and BDC Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Ford Service conversation flow answer the customer's request first, retain context, ask at most one next question, and create a truthful, idempotent BDC packet on handoff.

**Architecture:** Keep the existing Spring conversation orchestrator, Jev classifier, DeepSeek-only generation, transcript, lead journey, ticket queue, and outbox. Extend typed journey facts and ticket data; validate model proposals in application code and send handoff copy only after the ticket and outbound intent are durable.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, Spring Modulith 2.1.1, Liquibase 4.29.2, PostgreSQL, JUnit 5.

## Global Constraints

- Formal Mexican Spanish with usted; answer the current question before qualification; at most one useful next question.
- Preserve full transcript rows. Never delete/reset history at a message-count threshold.
- The five packet fields are model, payment method, approximate down payment when financing, purchase timeframe, and name.
- Handoff immediately on explicit human request, upset/complaint, or two refusals; packet may be partial.
- Never promise advisor response time or assert an advisor was notified; only say the request is in the Ford Service queue after durable queue creation.
- Do not add external CRM integration or the 8:45 digest. Do not store/log unnecessary sensitive data.

---

### Task 1: Add typed, evidence-backed packet facts

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/leads/domain/LeadJourneyFactKey.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/leads/application/LeadJourneyPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/LeadJourneyContextFormatter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/leads/application/LeadJourneyPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/application/LeadJourneyContextFormatterTest.java`

- [x] Add tests for exact user-evidenced facts, preserving customer wording, refusing unsupported payment/down-payment/name inference, and protecting accepted facts without new evidence.
- [x] Add the missing typed fact keys and format them in the existing context formatter with source message ID/timestamp.
- [x] Run the focused tests; covered again by the fresh 799-test Service unit suite.

### Task 2: Persist an idempotent BDC packet with the handoff ticket

**Files:**
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `ford-ai-service/src/main/resources/db/changelog/changes/021-bdc-handoff-packet.yaml`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/HandoffDecision.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/SpecialistHandoffPayloadFactory.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/handoff/domain/HandoffTicket.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/handoff/domain/HandoffRequest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/out/JdbcHandoffRepository.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/handoff/application/DefaultHandoffService.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/handoff/HandoffServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/DefaultConversationHandoffMessageServiceTest.java`

- [x] Test complete/partial packet, completeness count, sensitive-field omission, idempotent repeated handoff, and atomic ticket persistence.
- [x] Add additive Liquibase changeSet `025-bdc-handoff-packet.yaml` with packet JSONB and completeness metadata, keyed to the existing ticket.
- [x] Persist packet and handoff transition atomically using existing idempotency and transaction boundaries.
- [x] Run focused handoff tests, full Service unit suite, and database integration suite; all pass (three env-gated catalog snapshot tests skipped).

### Task 3: Enforce the conversation decision rules

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/SystemPromptService.java` and its prompt resource only as needed
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/application/DefaultConversationTurnProcessorTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptServiceTest.java`

- [x] Cover answer-first/one-question handling, captured-answer suppression, topic interruption, direct handoff, upset handoff, and second refusal using deterministic tests.
- [x] Keep decision/action permissions in application policy; provider output cannot create external actions or mark an advisor notified.
- [x] Run focused tests and the fresh full Service unit suite; 799/799 pass.

### Task 4: Make handoff customer copy reflect durable reality

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationHandoffMessageService.java`
- Modify: existing handoff/outbox event listener only if required by transaction ordering
- Test: corresponding `DefaultConversationHandoffMessageServiceTest.java` and outbox tests

- [x] Test ticket failure behavior, duplicate prevention, and truthful handoff copy without ETA/notification claims.
- [x] Use the existing outbox and ticket idempotency; persist before any outbound delivery request.
- [x] Run focused unit/integration tests; full Service unit suite passes and integration suite passes with three env-gated snapshot tests skipped.

## Completion Gate

- [x] Synthetic packet fixtures exceed 80% completeness where required facts are present; real conversation golden-set confirmation remains blocked on external examples.
- [x] Direct/complaint/two-refusal cases create correctly partial tickets in deterministic tests.
- [x] Full transcript remains intact; only validated user facts update journey state.
