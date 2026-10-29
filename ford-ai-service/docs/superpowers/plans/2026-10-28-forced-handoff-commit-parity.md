# Forced Handoff Commit Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal and forced handoffs share durable commit effects while keeping route choice server-owned, privacy-safe, and correctly separated between advisor review and mandatory human takeover.

**Architecture:** Keep `AdvisorResponseDispositionPolicy` responsible for selecting `AUTOMATIC`, `REVIEW`, or `MANDATORY_HANDOFF`. Extract the shared handoff request and accepted-effects sequence inside `ConversationTurnCommitService`; pass actual assistant decision/trace only from the normal assistant handoff path. Forced critical handoff bypasses assistant and stays decision-free. Thread sanitized staged contact through `DefaultConversationTurnProcessor`.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC, PostgreSQL/Testcontainers, JUnit 5, AssertJ, Mockito, Gradle.

## Global Constraints

- Use the production conversation graph and webhook; do not create an alternate graph or multi-agent flow.
- Critical or explicit-human requests take mandatory handoff even when a review route could otherwise apply.
- Routine uncertainty goes to advisor approval; supported and validated safe replies remain automatic.
- Confidence is system-grounded in evidence and quality outcomes; the model's self-reported confidence is not a standalone routing signal.
- Mandatory handoff stops bot replies.
- Keep seeded diagnostics, focused diagnosis, and fresh all-live acceptance separate.
- No production or `emme-2` writes; no catalog import until fresh acceptance passes.
- Preserve tenant scoping, optimistic locking, idempotency, transactional outbox behavior, and contact privacy.

---

## File Map

- `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationTurnCommitService.java` — shared handoff commit helper; normal and forced handoff call paths.
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` — pass staged contact into forced handoff; critical requests bypass assistant.
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/AdvisorResponseDispositionPolicy.java` and `AdvisorResponseRoutingPolicy.java` — preserve and verify criticality-first, system-grounded route precedence; no self-confidence threshold.
- `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationTurnCommitServiceTest.java` — unit regression tests for helper effects and CAS duplicate behavior.
- `ford-ai-service/src/test/java/com/ford/service/conversations/DefaultConversationTurnProcessorTest.java` — processor argument-forwarding and assistant-bypass tests.
- `ford-ai-service/src/test/java/com/ford/service/conversations/AdvisorResponseRoutingPolicyTest.java` — existing policy matrix; add serious-criticality-over-review assertion.
- `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/AlternateContactHandoffPrivacyIntegrationTest.java` — PostgreSQL proof for sanitized contact-bearing forced handoff.
- `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/JdbcMessageApprovalRepositoryIntegrationTest.java` — existing advisor hold, webhook invalidation, outbox, and rollback coverage; preserve and rerun.

### Task 1: Lock Route Precedence and Handoff Commit Expectations — COMPLETE

**Files:**
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/AdvisorResponseRoutingPolicyTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationTurnCommitServiceTest.java`

**Interfaces:**
- Consumes: existing `AdvisorResponseRoutingPolicy.decide(Signals)` and `ConversationTurnCommitService.forceHumanHandoff(...)` APIs.
- Produces: regression cases establishing mandatory-over-review precedence and accepted/rejected handoff side effects before refactoring.

- [x] **Step 1: Add routing precedence regression tests**

Extend `mandatorySignalsOverrideReviewSignalsAndFailuresOverrideOrdinaryReview` with `SERIOUS_COMPLAINT` plus `RESPONSE_REVIEW`, asserting `MANDATORY_HANDOFF` and `SERIOUS_COMPLAINT`. Existing cases already cover explicit-human precedence, routine review, and automatic verified turns. Do not add a numeric model-confidence threshold.

- [x] **Step 2: Run the policy test and confirm current behavior**

Run: `./gradlew test --tests com.ford.service.conversations.AdvisorResponseRoutingPolicyTest`
Expected: `BUILD SUCCESSFUL`; these tests pin existing server-owned precedence.

- [x] **Step 3: Add forced-handoff unit assertions**

Add accepted and rejected CAS cases in `ConversationTurnCommitServiceTest`. Assert accepted route uses `payload.reason()` in both `HandoffDecision` and `HandoffRequested`; records exactly one `HUMAN_PENDING` outcome and a handoff trace; persists only a supplied actual decision; includes provided alternate contact in the packet; and deletes staging only after acceptance. Assert a rejected CAS returns `DUPLICATE` and does not emit event, trace, ledger success, or staging deletion.

- [x] **Step 4: Run focused unit tests; use the PostgreSQL regression as the red case**

Run: `./gradlew test --tests com.ford.service.conversations.ConversationTurnCommitServiceTest --tests com.ford.service.conversations.AdvisorResponseRoutingPolicyTest`
Expected: focused unit suite passes. The PostgreSQL privacy integration is the red regression for actual assistant decision evidence lost on the post-assistant mandatory-handoff route.

### Task 2: Extract Shared Handoff Commit Effects — IN PROGRESS

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationTurnCommitService.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationTurnCommitServiceTest.java`

**Interfaces:**
- Consumes: `HandoffDecision`, `SpecialistHandoffPayload`, optional `ConversationDecision`, optional `DecisionTraceEvent` list, existing repository/ledger/trace/event ports.
- Produces: one private handoff commit helper accepting `ConversationTurnReference`, `AcceptedInboundMessage`, `ConversationSnapshot`, `HandoffDecision`, customer-safe `SpecialistHandoffPayload`, optional actual `ConversationDecision`, trace list, and confirmation text; returns `ConversationGraphRoute`.

- [ ] **Step 1: Implement the minimal shared helper**

Create a private helper with this signature:

```java
private ConversationGraphRoute commitHandoff(
        ConversationTurnReference reference,
        AcceptedInboundMessage inbound,
        ConversationSnapshot conversation,
        HandoffDecision handoff,
        com.ford.service.conversations.api.SpecialistHandoffPayload payload,
        com.ford.service.ai.application.ConversationDecision decision,
        java.util.List<DecisionTraceEvent> traceEvents,
        String confirmation)
```

It must reject mismatched `handoff.reason()` and `payload.reason()` before persistence; save a decision only when the supplied decision is actual; call `requestHumanHandoff` using the current snapshot version and existing idempotency key; return `DUPLICATE` without accepted-only effects on CAS failure; then write `HUMAN_PENDING`, append supplied traces plus one `handoff_requested` trace, publish `HandoffRequested` with matching safe reason/payload, clean staging, and return `HUMAN_PENDING`.

Move the current early `decisionRepository.save(...)` out of the common start of `commit(...)`: the shared helper saves the actual decision after handoff CAS succeeds; the ordinary bot-reply branch retains its existing decision persistence. This prevents duplicate persistence and prevents a failed handoff CAS from writing a `REQUIRES_NEW` decision row.

- [ ] **Step 2: Route ordinary handoff through the helper**

Replace the inline `repository.requestHumanHandoff` through event/cleanup sequence in `commit(...)` with `commitHandoff(...)`. Pass the existing assistant decision, `traceEvents(reply)`, normal priority/packet/internal note, and `HANDOFF_QUEUE_CONFIRMATION`. Keep all other commit and bot-reply behavior unchanged.

- [ ] **Step 3: Route forced handoff through the helper**

Replace the duplicate request/ledger/trace/event/cleanup sequence in `forceHumanHandoff(...)` with `commitHandoff(...)`. Use `payload.reason()`, retain the forced confirmation, preserve alternate contacts in the private packet, and pass `null` decision plus an empty model trace when no assistant ran.

- [ ] **Step 4: Run commit-service unit tests**

Run: `./gradlew test --tests com.ford.service.conversations.ConversationTurnCommitServiceTest`
Expected: `BUILD SUCCESSFUL`; accepted and rejected handoff cases pass.

### Task 3: Preserve Context Through Mandatory Routing

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/DefaultConversationTurnProcessorTest.java`

**Interfaces:**
- Consumes: current `stagedContacts`, optional `AssistantReply`, and `AdvisorResponseDisposition` from existing processor flow.
- Produces: mandatory handoff call passes staged contacts; critical pre-assistant requests still bypass the assistant and do not create a model decision.

- [ ] **Step 1: Add forwarding tests**

Add or update processor tests to capture the forced-handoff call with staged alternate contact and assert assistant is never called for direct human requests. Keep a review-only routine signal test proving it creates an approval hold without a handoff. Do not test an after-assistant mandatory route: current server policy routes assistant response-review signals to advisor approval, and critical signals are handled before assistant execution.

- [ ] **Step 2: Run processor tests and confirm the new forwarding assertions fail**

Run: `./gradlew test --tests com.ford.service.conversations.DefaultConversationTurnProcessorTest`
Expected: the new call-argument assertions fail before the processor forwards context.

- [ ] **Step 3: Extend the forced handoff overload and processor helper**

Retain the existing 5- and 6-argument `forceHumanHandoff` methods. Update the mandatory route to always call the 6-argument method with a non-null list (empty when no consented contact exists):

```java
forceHumanHandoff(ConversationTurnReference reference,
                 AcceptedInboundMessage inbound,
                 ConversationSnapshot conversation,
                 ConversationMemoryContext memory,
                 LeadJourneySnapshot journey,
                 java.util.List<AlternateContactValue> alternateContacts)
```

Keep `mandatoryHandoffOrHold` pre-assistant only. Pass `stagedContacts` to `forceHumanHandoff`; keep the safe replacement inbound and redacted memory behavior intact. The normal assistant handoff path passes its real decision/trace directly to the shared helper.

- [ ] **Step 4: Run processor and policy unit tests**

Run: `./gradlew test --tests com.ford.service.conversations.DefaultConversationTurnProcessorTest --tests com.ford.service.conversations.AdvisorResponseRoutingPolicyTest`
Expected: `BUILD SUCCESSFUL`; criticality wins, routine uncertainty holds, and normal safe replies are unchanged.

### Task 4: Verify Database and Production Webhook/Graph Behavior

**Files:**
- Modify only if regression evidence requires it: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/AlternateContactHandoffPrivacyIntegrationTest.java`
- Verify: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/JdbcMessageApprovalRepositoryIntegrationTest.java`
- Verify: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`

**Interfaces:**
- Consumes: shared helper and processor changes from Tasks 2–3.
- Produces: PostgreSQL evidence for privacy-safe forced handoff and existing real ingress approval invalidation; no catalog import or production database writes.

- [ ] **Step 1: Run focused PostgreSQL integration tests**

Run: `env -u FORD_QWEN_REPLAY_ENABLED -u FORD_QWEN_100_REPLAY_ENABLED -u FORD_QWEN_REPLAY_MODE -u FORD_QWEN_COMPARISON_PREFETCH_ENABLED ./gradlew integrationTest --tests com.ford.service.conversations.AlternateContactHandoffPrivacyIntegrationTest --tests com.ford.service.conversations.JdbcMessageApprovalRepositoryIntegrationTest`
Expected: all selected integration tests pass; contact remains only in the private handoff packet, staging is removed, turn/trace rows exist, approval is invalidated on accepted inbound, and outbound/outbox writes remain atomic.

- [ ] **Step 2: Run the full unit suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` with zero failures.

- [ ] **Step 3: Run the full integration suite with the approved disposable snapshot**

Run: `FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" env -u FORD_QWEN_REPLAY_ENABLED -u FORD_QWEN_100_REPLAY_ENABLED -u FORD_QWEN_REPLAY_MODE -u FORD_QWEN_COMPARISON_PREFETCH_ENABLED ./gradlew integrationTest`
Expected: integration suite passes; all databases are disposable and no Qwen replay is enabled. If the local BGE container times out, report that environmental failure separately and rerun only that test before proceeding.

- [ ] **Step 4: Run fresh 100-turn live Qwen acceptance only after unit and integration gates pass**

Run:

```bash
env -u FORD_QWEN_REPLAY_SEED_TRANSCRIPT \
  -u FORD_QWEN_REPLAY_SEED_THROUGH_TURN \
  -u FORD_QWEN_REPLAY_FORCE_LIVE_TURNS \
  -u FORD_QWEN_REPLAY_CHECKPOINT \
  -u FORD_QWEN_TARGET_START_TURN \
  -u FORD_QWEN_TARGET_END_TURN \
  -u FORD_QWEN_REPLAY_CHECKPOINT_CAPTURE_TURN \
  FORD_QWEN_REPLAY_ENABLED=true \
  FORD_QWEN_100_REPLAY_ENABLED=true \
  FORD_QWEN_REPLAY_MODE=FRESH_FULL_LIVE \
  FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
  FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
  FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
  ./gradlew integrationTest --rerun-tasks \
  --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest.preflightsLocalCatalogSnapshotAndVectorRetrieval \
  --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest.acceptsFreshOneHundredTurnAllLiveRunOnly
```

Do not use seeded or focused modes as acceptance substitutes.
Expected: all 100 turns commit; review transcript and telemetry for unsupported/conflicted catalog claims, safe fallbacks, handoff behavior, and actual provider calls. Catalog import remains blocked unless this run and evidence review pass.

- [ ] **Step 5: Inspect changes and report exact verification state**

Run: `git diff --check` and `git status --short`.
Expected: no newly introduced whitespace errors; report any pre-existing unrelated worktree changes without modifying or discarding them.
