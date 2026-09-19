# Ford Service Phase A Spring Modulith Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing Ford chatbot scaffold into a secure, durable Meta-to-assistant/handoff vertical slice using Spring Modulith for application boundaries and internal event reliability.

**Architecture:** Keep Spring MVC with Java virtual threads, PostgreSQL, JDBC/`JdbcClient`, Liquibase, Spring AI, and a custom provider-delivery outbox. Spring Modulith owns module verification, internal transactional event publication, listener recovery, module tests, documentation, topology, and module interaction observations. The webhook transaction is short; model/tool work runs outside database transactions; a CAS-protected second transaction persists either a reply/outbox event or a human handoff.

**Tech Stack:** Spring Boot 4.1.0, Spring Modulith 2.1.1 BOM, Java 25, Spring MVC, virtual threads, Spring AI 2.0.1, PostgreSQL/pgvector, `JdbcClient`, Liquibase, Redis, Testcontainers, OpenTelemetry, Langfuse.

## Global Constraints

- Use Spring Modulith 2.1.1 through one BOM; no milestone or snapshot dependencies.
- Use `spring-modulith-starter-jdbc` for the JDBC event publication registry.
- Use `spring-modulith-starter-insight` for protected Modulith actuator topology and module interaction observations.
- Use `spring-modulith-starter-test` for `@ApplicationModuleTest`, `Scenario`, and published-event assertions.
- Do not introduce LangGraph4j, JPA, MongoDB, a broker, or a second migration system in Phase A.
- Do not use a caller-supplied tenant header; tenant identity comes from a verified Meta channel binding.
- Do not hold a JDBC transaction open during model calls, retrieval, tool calls, scraping, or provider HTTP calls.
- Model tools are read-only; lead scoring, state transitions, handoff, and outbound sending remain application-owned.
- Modulith’s event publication registry is for internal module listeners; `outbox_event` remains the external provider-delivery boundary.
- Every task follows Red → Green → Refactor and commits one coherent logical change.
- Do not log credentials, raw Meta payloads, prompts, completions, or sensitive lead data without the existing redaction policy.

---

## File Map

### New files

- `ford-ai-service/src/test/java/com/ford/service/architecture/ApplicationModulesTest.java`
- `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleDocumentationTest.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/api/ChannelBinding.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/api/InboundMessageAccepted.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelBindingService.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/application/InboundWebhookService.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/application/port/out/ChannelBindingRepository.java`
- `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/JdbcChannelBindingRepository.java`
- `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationTurnReady.java`
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationRepository.java`
- `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantTurn.java`
- `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleSnapshotTool.java`
- `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/FinanceEstimateTool.java`
- `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxDeliveryRouter.java`
- `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxRecoveryJob.java`
- `ford-ai-service/src/test/java/com/ford/service/channels/ChannelBindingServiceTest.java`
- `ford-ai-service/src/integrationTest/java/com/ford/service/channels/MetaInboundWorkflowIntegrationTest.java`
- `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/ConversationScenarioIntegrationTest.java`
- `ford-ai-service/src/integrationTest/java/com/ford/service/outbox/OutboxRecoveryIntegrationTest.java`

### Existing files to modify

- `ford-ai-service/gradle/libs.versions.toml`, `ford-ai-service/build.gradle.kts`
- `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
- `ford-ai-service/src/main/resources/application.yml`
- `ford-ai-service/src/main/resources/db/changelog/changes/001-platform.yaml`
- `ford-ai-service/src/main/resources/db/changelog/changes/003-channel-message.yaml`
- `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Meta controller/adapter/domain under `ford-ai-service/src/main/java/com/ford/service/channels`
- Conversation service/port under `ford-ai-service/src/main/java/com/ford/service/conversations`
- Spring AI orchestration/tools/provider configuration under `ford-ai-service/src/main/java/com/ford/service/ai`
- Outbox store/publisher/job and Meta delivery adapter under `ford-ai-service/src/main/java/com/ford/service/outbox` and `.../channels/adapter/out/meta`
- Handoff service/repository/controller and `SecurityConfiguration.java`
- Existing related unit/integration tests and `docs/observability.md`, `docs/ci-cd.md`

---

## Task 1: Add and verify the Spring Modulith foundation

**Files:**

- Modify: `ford-ai-service/gradle/libs.versions.toml`, `ford-ai-service/build.gradle.kts`
- Modify: `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Create: `ford-ai-service/src/test/java/com/ford/service/architecture/ApplicationModulesTest.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleDocumentationTest.java`

**Interfaces produced:** `ApplicationModules.of(FordServiceApplication.class).verify()`; generated Modulith docs under `build/spring-modulith-docs`; protected `modulith` actuator resource.

- [ ] **Step 1: Write the failing module test**

```java
@Test
void applicationModulesHaveNoCyclesOrInternalAccessViolations() {
    ApplicationModules.of(FordServiceApplication.class).verify();
}
```

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:test --tests com.ford.service.architecture.ApplicationModulesTest`

Expected: FAIL because Modulith is not yet present and/or the current package graph violates its rules.

- [ ] **Step 3: Add dependencies and configuration**

Add the Modulith BOM plus JDBC, insight, and test starters. Add `@Modulithic(systemName = "ford-service")`. Configure runtime verification, JDBC event publication lifecycle/staleness, and externalization disabled. Resolve the exact dependency graph before proceeding.

- [ ] **Step 4: Make the smallest boundary changes required for Green**

Add `package-info.java`/`@ApplicationModule` only where the verification reports a real boundary. Use public API packages for cross-module types; keep adapters and repositories internal. Do not blanket-exclude violations.

- [ ] **Step 5: Add documentation verification**

```java
@Test
void writesModuleDiagramsAndCanvases() {
    ApplicationModules modules = ApplicationModules.of(FordServiceApplication.class);
    new Documenter(modules)
        .writeModulesAsPlantUml()
        .writeIndividualModulesAsPlantUml()
        .writeModuleCanvases()
        .writeAggregatingDocument();
}
```

Run: `./gradlew :ford-ai-service:test --tests '*ApplicationModulesTest' --tests '*ModuleDocumentationTest'`

Expected: PASS and generated diagrams/canvases under the build directory.

- [ ] **Step 6: Refactor, verify, commit**

Run `./gradlew :ford-ai-service:test`, inspect the generated module graph, then commit:

```bash
git add ford-ai-service/gradle ford-ai-service/build.gradle.kts ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java ford-ai-service/src/main/resources/application.yml ford-ai-service/src/test/java/com/ford/service/architecture
git commit -m "feat(modulith): add verified application module foundation"
```

## Task 2: Secure Meta binding resolution and durable inbound events

**Files:**

- Create `channels/api/ChannelBinding.java`, `channels/api/InboundMessageAccepted.java`
- Create `channels/application/ChannelBindingService.java`, `channels/application/InboundWebhookService.java`
- Create `channels/application/port/out/ChannelBindingRepository.java`, `channels/adapter/out/JdbcChannelBindingRepository.java`
- Modify Meta controller, adapter, `ChannelMessageService`, `RawWebhook`
- Modify `003-channel-message.yaml` and changelog master
- Create `ChannelBindingServiceTest.java`, `MetaInboundWorkflowIntegrationTest.java`

**Interfaces produced:**

```java
public interface ChannelBindingRepository {
    Optional<ChannelBinding> findEnabledByWebhookKey(String webhookKey);
}

public record ChannelBinding(
    UUID id, UUID tenantId, Channel channel,
    String providerAccountId, String secretReference, boolean enabled) {}

public record InboundMessageAccepted(
    UUID receiptId, UUID tenantId, UUID conversationId,
    Channel channel, String providerMessageId, Instant acceptedAt) {}
```

- [ ] **Step 1: Write Red tests**

Cover unknown/disabled keys, valid tenant resolution, forged `X-Tenant-Id` rejection, invalid signature/account rejection before persistence, and duplicate provider delivery publishing no second event.

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:test --tests '*ChannelBindingServiceTest'`

Expected: FAIL because the binding port and ingress use case do not exist.

- [ ] **Step 3: Add schema and binding adapter**

Add a Liquibase changeset with hashed opaque key, tenant/channel/provider account, secret reference, enabled flag, timestamps, and unique key hash. Add `binding_id` and `conversation_id` to channel receipt/message tables and binding/provider indexes.

- [ ] **Step 4: Implement the short transactional ingress**

Change the endpoint to `POST /webhooks/meta/{channel}/{webhookKey}`. Resolve binding, verify HMAC with its secret, validate provider account ID, deduplicate, persist receipt/message/conversation identity, publish `InboundMessageAccepted`, and return 202 after commit. Remove tenant authority from request headers.

- [ ] **Step 5: Verify with PostgreSQL**

```java
@Test
void acceptsValidBindingAndPersistsModulithPublication() {
    // POST a signed fixture to /webhooks/meta/whatsapp/{webhookKey}.
    // Assert 202, one receipt/message/conversation, and one publication.
}
```

Run: `./gradlew :ford-ai-service:integrationTest --tests '*MetaInboundWorkflowIntegrationTest'`

Expected: PASS; replay does not create a second event.

- [ ] **Step 6: Commit**

```bash
git add ford-ai-service/src/main/java/com/ford/service/channels ford-ai-service/src/main/resources/db/changelog ford-ai-service/src/test/java/com/ford/service/channels ford-ai-service/src/integrationTest/java/com/ford/service/channels
git commit -m "feat(channels): secure Meta bindings and publish inbound events"
```

## Task 3: Add durable conversation workflow and CAS-protected handoff

**Files:**

- Create `conversations/api/ConversationTurnReady.java`
- Create `conversations/application/ConversationWorkflow.java`
- Create `conversations/adapter/out/JdbcConversationRepository.java`
- Modify `ConversationService.java`, `ConversationRepository.java`, handoff repository/service
- Modify `001-platform.yaml`
- Create `ConversationScenarioIntegrationTest.java`

**Interfaces produced:**

```java
public interface ConversationRepository {
    Optional<ConversationSnapshot> findForUpdate(UUID conversationId);
    boolean appendInboundIfNew(UUID conversationId, String providerMessageId, String text, Instant receivedAt);
    boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn);
    boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision);
}
```

- [ ] **Step 1: Write Red tests**

Test inbound event processing, duplicate provider ID idempotency, active-bot reply plus `OutboundDeliveryRequested`, no reply in `HUMAN_ACTIVE`, and stale version rejection after a human claim.

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:test --tests '*ConversationStateTransitionTest'`

Expected: FAIL because no JDBC conversation adapter or event listener exists.

- [ ] **Step 3: Add versioned persistence**

Add conversation version/provider identity constraints. Implement repository updates with `JdbcClient`; every mutation scopes tenant/conversation and checks expected version, incrementing only on success.

- [ ] **Step 4: Implement listener transaction boundaries**

Use `@ApplicationModuleListener` without putting a transaction around the listener method. Keep model/retrieval/tool calls outside JDBC transactions. Delegate the final reply/outbox or handoff write to a separate `@Transactional(propagation = REQUIRES_NEW)` service. A failed CAS records a stale-result observation and sends nothing.

- [ ] **Step 5: Verify with Scenario**

```java
@ApplicationModuleTest
class ConversationScenarioIntegrationTest {
    @Test
    void inboundEventProducesReplyForActiveBot(Scenario scenario) {
        scenario.publish(new InboundMessageAccepted(/* fixture */))
            .andWaitForEventOfType(OutboundDeliveryRequested.class)
            .toArriveAndVerify(event ->
                assertThat(event.tenantId()).isEqualTo(TEST_TENANT));
    }
}
```

Run: `./gradlew :ford-ai-service:integrationTest --tests '*ConversationScenarioIntegrationTest'`

Expected: PASS without sleeps.

- [ ] **Step 6: Commit**

```bash
git add ford-ai-service/src/main/java/com/ford/service/conversations ford-ai-service/src/main/java/com/ford/service/handoff ford-ai-service/src/main/resources/db/changelog/changes/001-platform.yaml ford-ai-service/src/integrationTest/java/com/ford/service/conversations
git commit -m "feat(conversations): process inbound events with durable CAS workflow"
```

## Task 4: Wire Spring AI providers and read-only tools

**Files:**

- Create `ai/api/AssistantTurn.java`, `ai/application/SpringAiAssistantService.java`
- Create `ai/adapter/in/tool/VehicleSnapshotTool.java`, `FinanceEstimateTool.java`
- Modify existing AI orchestrator, policy, tools, and provider configuration
- Create `SpringAiAssistantServiceTest.java`; modify `ToolAuthorizationPolicyTest.java`

**Interfaces produced:**

```java
public interface AssistantService {
    AssistantTurn respond(AiExecutionContext context, String userText);
}

public record AssistantTurn(
    String text, Intent intent, boolean requestsHuman,
    List<String> sourceIds, String provider, String modelVersion) {}
```

- [ ] **Step 1: Write Red tests**

Assert vehicle questions can use vehicle snapshots; finance questions use only finance/approved knowledge; mutation/send/SQL tools are never exposed; tenant/locale come from tool context; and provider timeout yields deterministic fallback/handoff.

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:test --tests '*ToolAuthorizationPolicyTest' --tests '*SpringAiAssistantServiceTest'`

Expected: FAIL because the allowlist and provider/tool wiring are incomplete.

- [ ] **Step 3: Implement minimal Spring AI composition**

Register local/cloud `ChatClient` instances, select one via existing `ModelProviderPolicy`, attach allowlisted tools, pass tenant/locale separately through tool context, and preserve source/freshness metadata. Keep observations and redaction enabled.

- [ ] **Step 4: Add only deterministic read methods**

Expose catalog, finance, and approved knowledge lookups. Do not expose handoff, lead scoring, outbound, credit decisions, SQL, or ingestion as tools. Mark finance wording non-binding.

- [ ] **Step 5: Verify and commit**

Run `./gradlew :ford-ai-service:test --tests '*ToolAuthorizationPolicyTest' --tests '*SpringAiAssistantServiceTest' --tests '*AssistantOrchestratorTest'`, expect PASS without network calls, then:

```bash
git add ford-ai-service/src/main/java/com/ford/service/ai ford-ai-service/src/test/java/com/ford/service/ai
git commit -m "feat(ai): wire Spring AI providers and read-only tools"
```

## Task 5: Complete the external provider outbox

**Files:**

- Create `outbox/OutboxDeliveryRouter.java`, `outbox/OutboxRecoveryJob.java`
- Modify `OutboxDeliveryJob.java`, `JdbcOutboxStore.java`, `OutboxPublisher.java`, Meta delivery adapter
- Modify `001-platform.yaml`
- Create `OutboxRecoveryIntegrationTest.java`

**Interfaces produced:**

```java
public interface OutboxDeliveryRouter {
    NotificationResult deliver(OutboxEvent event);
}

public interface OutboxStore {
    Optional<OutboxEvent> claimNext(Instant now, Duration lease);
    int reclaimExpired(Instant now);
    void markDelivered(UUID eventId, String providerDeliveryId, Instant deliveredAt);
    void markRetry(UUID eventId, String category, Instant nextAttemptAt);
    void moveToDeadLetter(UUID eventId, String category, String reason);
}
```

- [ ] **Step 1: Write Red tests**

Test stable business-key deduplication, lease reclamation, bounded exponential backoff, dead-letter for permanent failures, provider ID persistence, and repeated delivery with the same idempotency key.

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:test --tests '*OutboxPublisherTest'`; expect failure on missing recovery/wiring. The Testcontainers recovery test runs in Step 5 after it has been created.

- [ ] **Step 3: Add durable retry state**

Add attempt count, next attempt, lease expiry, idempotency key, provider ID, last error category, and dead-letter state. Claim due work with `FOR UPDATE SKIP LOCKED`; reclaim expired leases explicitly.

- [ ] **Step 4: Wire the router and worker**

Register channel delivery adapters, make the scheduled worker a bean, keep provider HTTP outside DB transactions, and update delivery state in a short transaction.

- [ ] **Step 5: Verify and commit**

Run `./gradlew :ford-ai-service:integrationTest --tests '*OutboxRecoveryIntegrationTest' --tests '*OutboxIntegrationTest'`; expect PASS, then:

```bash
git add ford-ai-service/src/main/java/com/ford/service/outbox ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/meta ford-ai-service/src/main/resources/db/changelog/changes/001-platform.yaml ford-ai-service/src/integrationTest/java/com/ford/service/outbox
git commit -m "feat(outbox): add durable provider delivery recovery"
```

## Task 6: Secure operator actions and connect handoff events

**Files:**

- Modify handoff controller/service/repository and `SecurityConfiguration.java`
- Modify `HandoffServiceTest.java`, `HandoffApiIntegrationTest.java`, and `application.yml`

**Interfaces produced:**

```java
public record OperatorPrincipal(UUID operatorId, Set<UUID> tenantIds, Set<String> authorities) {}
```

- [ ] **Step 1: Write Red tests**

Assert cross-tenant claim rejection, expected-version claim/pause/resume, audit plus notification event in one transaction, and controller ignoring tenant/operator body/query values when claims are present.

- [ ] **Step 2: Run Red**

Run the focused handoff unit/integration tests; expect failure on caller-controlled identity and absent CAS/audit behavior.

- [ ] **Step 3: Implement claim-based authorization**

Read identity from Spring Security authentication, scope every repository query by tenant, require expected ticket version, and write audit plus transition in one transaction. Keep management/actuator access separate from customer APIs.

- [ ] **Step 4: Verify and commit**

Run focused tests, then:

```bash
git add ford-ai-service/src/main/java/com/ford/service/handoff ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java ford-ai-service/src/main/resources/application.yml ford-ai-service/src/test/java/com/ford/service/handoff ford-ai-service/src/integrationTest/java/com/ford/service/handoff
git commit -m "feat(handoff): enforce tenant claims and auditable takeover"
```

## Task 7: Finish the vertical integration contract and operational checks

**Files:**

- Create `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java`
- Modify existing Meta, finance, takeover, and outbox E2E tests
- Modify `docs/observability.md`, `docs/ci-cd.md`, and this plan

- [ ] **Step 1: Write the failing full vertical test**

```java
@Test
void signedMetaVehicleQuestionProducesOneGroundedOutboxReply() {
    // POST signed fixture to /webhooks/meta/whatsapp/{webhookKey}.
    // Await the Modulith event scenario.
    // Assert Spanish/MXN response, source/freshness, one outbox key,
    // no second reply on replay, and no raw prompt/payload leakage.
}
```

- [ ] **Step 2: Run Red**

Run: `./gradlew :ford-ai-service:integrationTest --tests '*MetaToOutboxVerticalFlowTest'`; expect failure until the seams are connected.

- [ ] **Step 3: Add only composition-root wiring**

Register listeners, repositories, tool beans, delivery router, scheduled recovery, and observations through Spring configuration. Do not add a service locator or generic factory.

- [ ] **Step 4: Verify the complete project**

Run:

```bash
./gradlew clean check
```

Expected: all unit, integration, architecture, CI, observability, Modulith documentation, and event-recovery tests pass with zero skipped tests.

- [ ] **Step 5: Update status and commit**

Mark tasks `✅`, set plan status to Complete, document Modulith event recovery and management endpoint behavior, then:

```bash
git add docs ford-ai-service
git commit -m "feat(ford-service): complete Modulith Phase A vertical flow"
```

## Dependency Notes

```text
Task 1
  ├─> Task 2 (secure inbound + InboundMessageAccepted)
  ├─> Task 4 (Spring AI tools)
  ├─> Task 5 (provider outbox)
  └─> Task 6 (operator claims)

Task 2 + Task 4 + Task 5 + Task 6
  └─> Task 3 (conversation workflow integration)
  └─> Task 7 (full vertical verification)
```

Tasks 2, 4, 5, and 6 are disjoint after Task 1 and can be assigned to separate subagents. Task 3 integrates their public contracts and must be reviewed against the module graph before Task 7.

## Definition of Done

- [ ] Modulith BOM resolves on the project’s Boot 4.1.x line.
- [ ] `ApplicationModules.verify()` passes.
- [ ] JDBC event publications commit with source transactions.
- [ ] Failed/stale publications have tested recovery/resubmission behavior.
- [ ] Module documentation and protected actuator topology are generated.
- [ ] Meta tenant resolution is binding-based; no caller-controlled tenant header remains.
- [ ] Duplicate inbound messages are idempotent.
- [ ] Model calls run outside JDBC transactions.
- [ ] Human takeover prevents stale assistant delivery.
- [ ] Spring AI exposes only read-only catalog/finance/knowledge tools.
- [ ] Provider outbox has lease recovery, backoff, stable idempotency, and provider IDs.
- [ ] Operator actions are tenant-scoped, claim-based, CAS-protected, and audited.
- [ ] Testcontainers flow passes from signed Meta webhook to reply/handoff.
- [ ] Existing unit, integration, CI, and observability tests remain green.
- [ ] All implementation changes are committed and pushed to `feat/ford-service-mvp`.
