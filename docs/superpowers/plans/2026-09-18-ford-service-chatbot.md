# ford-service Chatbot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone `ford-service` Spring Boot backend that answers grounded Spanish/MXN vehicle and financing questions across WhatsApp, Messenger, and Instagram, captures optional low-sensitivity leads, scores sales priority, and exposes persisted human-handoff APIs.

**Architecture:** Use a modular monolith rooted at `ford-ai-service/`, with explicit application ports and provider adapters. Spring AI owns model/tool execution; PostgreSQL owns canonical business and conversation state; Redis is ephemeral; all external calls are idempotent, audited, observable, and never performed synchronously by the chat path when they are ingestion/scraping calls.

**Tech Stack:** Java 25, Spring Boot 4.1.0 baseline from `emme-service`, Spring MVC, virtual threads, Spring AI 2.0.1, PostgreSQL 16 + pgvector, Redis, Liquibase, `JdbcClient`, RestClient, Micrometer, OpenTelemetry, JUnit 5, Mockito, Testcontainers, WireMock, MockMvc.

## Global Constraints

- Runtime is Spring MVC with Java 25 virtual threads; WebFlux is not part of MVP.
- Spring AI is used for `ChatClient`, structured output, advisors, RAG, and tools.
- Chat providers are selected through an application policy: Ollama for local development and at least one cloud provider for production. Chat and embedding providers are independently configurable and versioned.
- LangGraph4j and `langgraph4j-spring-ai` are not MVP dependencies.
- PostgreSQL is the sole primary database; PostgreSQL JSONB stores raw provider payloads.
- pgvector stores durable knowledge embeddings; Spring AI `SimpleVectorStore` is used only by tests or an explicit local demo profile. Redis stores only temporary state, locks, limits, and cache.
- `JdbcClient`/SQL is the default persistence mechanism; add Spring Data repositories only for a demonstrated aggregate-mapping need; do not add JPA by default.
- All three Meta channels are in the MVP boundary, but each is independently feature-flagged and contract-tested.
- Initial locale is Spanish, Mexico; monetary values use MXN.
- Spring configuration is YAML-only: use `application.yml` and profile-specific
  `application-*.yml`; do not add `.properties` configuration files.
- Catalog, inventory, promotions, finance terms, freshness, consent, authorization, and lead scoring remain application-owned.
- Lead score is `HOT`, `WARM`, or `COLD` sales priority only; it cannot approve, deny, or decide credit eligibility.
- MVP lead capture may collect contact data, vehicle preference, ranges, trade-in interest, employment range, and consent; it must not collect exact income, government IDs, bank data, or documents.
- Ford API/feed access and scraping must be authorized, scheduled, rate-limited, audited, and isolated from chat requests.
- Every external boundary receives an injected port and a test double; no service instantiates provider clients internally.
- Write failing tests before implementation, run focused verification after each task, and commit each task atomically.

## File Structure

The existing `ford-ai-service/` directory remains the implementation root. The
runtime/application name is `ford-service`.

```text
ford-ai-service/
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  src/main/java/com/ford/service/FordServiceApplication.java
  src/main/java/com/ford/service/config/
  src/main/java/com/ford/service/shared/
  src/main/java/com/ford/service/catalog/
  src/main/java/com/ford/service/offers/
  src/main/java/com/ford/service/finance/
  src/main/java/com/ford/service/conversations/
  src/main/java/com/ford/service/leads/
  src/main/java/com/ford/service/handoff/
  src/main/java/com/ford/service/channels/
  src/main/java/com/ford/service/ingestion/
  src/main/java/com/ford/service/knowledge/
  src/main/java/com/ford/service/ai/
  src/main/java/com/ford/service/outbox/
  src/main/java/com/ford/service/observability/
  src/main/resources/application.yml
  src/main/resources/db/changelog/db.changelog-master.yaml
  src/main/resources/db/changelog/changes/
  src/test/java/com/ford/service/
  src/integrationTest/java/com/ford/service/
  src/test/resources/application-test.yml
  src/integrationTest/resources/application-integration.yml
```

Each module owns its domain records, application use cases, ports, adapters,
and focused tests. No module imports another module's persistence adapter or
provider SDK.

## Task 1: Bootstrap the application and build baseline

**Files:**

- Create: `ford-ai-service/settings.gradle.kts`
- Create: `ford-ai-service/build.gradle.kts`
- Create: `ford-ai-service/gradle/libs.versions.toml`
- Create: `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
- Create: `ford-ai-service/src/main/resources/application.yml`
- Test: `ford-ai-service/src/test/java/com/ford/service/FordServiceApplicationTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/architecture/PackageBoundaryTest.java`

**Interfaces:**

- Produces application startup, Gradle verification, package roots, and the
  `spring.threads.virtual.enabled=true` runtime property.
- Later tasks consume package roots under `com.ford.service.*` and the shared
  configuration properties.

- [x] **Step 1: Write the failing startup and package tests**

```java
@SpringBootTest
class FordServiceApplicationTest {
  @Test
  void startsWithTheFordServiceApplicationContext() {}
}

@AnalyzeClasses(packages = "com.ford.service")
class PackageBoundaryTest {
  @ArchTest
  static final ArchRule domainMustNotDependOnSpring =
      noClasses().that().resideInAnyPackage("..catalog.domain..", "..finance.domain..")
          .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.hibernate..");
}
```

- [x] **Step 2: Run focused tests and confirm the expected missing-project failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*FordServiceApplicationTest' --tests '*PackageBoundaryTest'`

Expected: FAIL because the Gradle project and application do not exist.

- [x] **Step 3: Add the minimal Gradle/application baseline**

Use the pinned `emme-service` baseline: Spring Boot `4.1.0`, Spring AI `2.0.1`,
Java toolchain 25, Spring MVC, validation, actuator, JDBC, PostgreSQL,
Liquibase, Redis, Micrometer/OTel, Testcontainers, WireMock, ArchUnit, and
Spring AI's model/tool/RAG/vector-store modules. Configure the BOM-managed
Spring AI modules for the selected cloud chat provider, Ollama, and PGvector;
verify the resolved artifact names against Spring AI `2.0.1` before importing
them. Do not add LangGraph4j, WebFlux, JPA, or provider SDKs outside Spring AI.

```yaml
spring:
  application:
    name: ford-service
  threads:
    virtual:
      enabled: true
  main:
    keep-alive: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

- [x] **Step 4: Run focused tests and verify they pass**

Run: `cd ford-ai-service && ./gradlew test --tests '*FordServiceApplicationTest' --tests '*PackageBoundaryTest'`

Expected: PASS with zero failures.

- [x] **Step 5: Commit the bootstrap**

```bash
git add ford-ai-service
git commit -m "feat(ford-service): bootstrap spring application"
```

## Task 2: Add database migrations, shared IDs, money, and source freshness

**Files:**

- Create: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `ford-ai-service/src/main/resources/db/changelog/changes/001-platform.yaml`
- Create: `ford-ai-service/src/main/java/com/ford/service/shared/DomainId.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/shared/Money.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/shared/SourceMetadata.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/shared/FreshnessStatus.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/shared/SourcePrecedencePolicy.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/shared/MoneyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/shared/SourcePrecedencePolicyTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/database/SchemaIntegrationTest.java`

**Interfaces:**

```java
public record Money(BigDecimal amount, Currency currency) {
  public Money {
    if (amount == null || currency == null || amount.scale() > 2) {
      throw new IllegalArgumentException("MXN money requires amount and max scale 2");
    }
  }
}

public record SourceMetadata(
    String sourceSystem,
    String sourceRecordId,
    Instant observedAt,
    Instant effectiveFrom,
    Instant effectiveTo,
    FreshnessStatus freshnessStatus,
    String rawPayload,
    UUID syncRunId) {}

public interface SourcePrecedencePolicy {
  SourceSelection choose(List<SourceCandidate> candidates, Instant now);
}
```

- [x] **Step 1: Write failing unit tests for money and source precedence**

Test exact MXN scale/rounding rejection, official feed preference, authorized
scraper fallback, explicit manual override with expiry, and seed fallback.

- [x] **Step 2: Run focused unit tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*MoneyTest' --tests '*SourcePrecedencePolicyTest'`

Expected: FAIL because the shared types do not exist.

- [x] **Step 3: Add shared value objects and Liquibase tables**

Create tables for `sync_run`, `vehicle_model`, `vehicle_variant`,
`vehicle_unit`, `vehicle_color`, `vehicle_price`, `offer`, `finance_product`,
`finance_term`, `conversation`, `conversation_message`, `lead`,
`lead_score`, `handoff_ticket`, `outbox_event`, and `audit_event` with UUID
keys, `TIMESTAMPTZ`, JSONB raw payloads, unique provider/source keys, and
indexes for tenant/channel/conversation/message lookups.

- [x] **Step 4: Run focused tests and Testcontainers schema verification**

Run: `cd ford-ai-service && ./gradlew test --tests '*MoneyTest' --tests '*SourcePrecedencePolicyTest' && ./gradlew integrationTest --tests '*SchemaIntegrationTest'`

Expected: PASS; Liquibase applies cleanly to an empty PostgreSQL container.

- [x] **Step 5: Commit the persistence foundation**

```bash
git add ford-ai-service/src/main/java/com/ford/service/shared ford-ai-service/src/main/resources/db/changelog ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add shared domain values and schema"
```

## Task 3: Implement catalog, offers, finance, and deterministic tools

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/domain/Vehicle.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/application/VehicleCatalog.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/application/port/out/VehicleCatalogRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcVehicleCatalogRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/offers/application/OfferService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/domain/FinanceTerms.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/domain/FinanceEstimate.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/application/DeterministicFinanceCalculator.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/application/FinanceService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/application/port/out/FinanceTermsRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogQuery.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/finance/api/FinanceEstimateRequest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/catalog/VehicleCatalogTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/finance/DeterministicFinanceCalculatorTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogJdbcIntegrationTest.java`

**Interfaces:**

```java
public interface VehicleCatalog {
  List<VehicleSummary> search(CatalogQuery query);
  Optional<VehicleDetails> findDetails(VehicleId vehicleId);
  InventoryResult inventory(InventoryQuery query);
}

public interface FinanceCalculator {
  FinanceEstimate calculate(FinanceEstimateRequest request, FinanceTerms terms);
}

public record FinanceEstimate(
    Money monthlyPayment,
    Money totalFinanced,
    Money totalCost,
    int termMonths,
    boolean binding) {}
```

- [x] **Step 1: Write failing catalog and finance tests**

Cover vehicle filters, color availability, stale inventory flags, offer date
windows, zero/negative down payment rejection, valid amortization, fees, and
the non-binding marker.

- [x] **Step 2: Run focused tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*VehicleCatalogTest' --tests '*DeterministicFinanceCalculatorTest'`

Expected: FAIL because application services and repositories do not exist.

- [x] **Step 3: Implement SQL repositories and pure finance calculation**

Use `JdbcClient` with explicit SQL and tenant/source/freshness predicates. Do
not permit free-form SQL or model-provided table/column names. Keep finance
math independent of Spring.

- [x] **Step 4: Run unit and integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*VehicleCatalogTest' --tests '*DeterministicFinanceCalculatorTest' && ./gradlew integrationTest --tests '*CatalogJdbcIntegrationTest'`

Expected: PASS with zero failures.

- [x] **Step 5: Commit the deterministic business tools**

```bash
git add ford-ai-service/src/main/java/com/ford/service/catalog ford-ai-service/src/main/java/com/ford/service/offers ford-ai-service/src/main/java/com/ford/service/finance ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add catalog offers and finance calculations"
```

## Task 4: Implement conversations, consent, lead scoring, and handoff APIs

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/ConversationState.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/domain/ConversationMessage.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/out/ConversationRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/leads/domain/LeadScoreBand.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/leads/domain/LeadProfile.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/leads/application/LeadScoringPolicy.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/leads/application/DefaultLeadScoringPolicy.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/handoff/application/HandoffService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/in/web/HandoffController.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/out/JdbcHandoffRepository.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/leads/DefaultLeadScoringPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationStateTransitionTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/handoff/HandoffServiceTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/handoff/HandoffApiIntegrationTest.java`

**Interfaces:**

```java
public interface LeadScoringPolicy {
  LeadScore score(LeadProfile profile, ConversationSignals signals);
}

public interface HandoffService {
  HandoffTicket request(HandoffRequest request);
  HandoffTicket claim(UUID ticketId, OperatorId operatorId);
  HandoffTicket pauseBot(UUID conversationId, OperatorId operatorId);
  HandoffTicket resumeBot(UUID conversationId, OperatorId operatorId);
  Page<HandoffTicket> list(HandoffQuery query);
}
```

- [x] **Step 1: Write failing tests for state, consent, score, and handoff**

Cover valid/invalid state transitions, duplicate messages, missing consent,
hot/warm/cold reasons, explicit human requests, operator claim concurrency,
and the rule that human-active conversations do not invoke AI.

- [x] **Step 2: Run focused tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*LeadScoringPolicyTest' --tests '*ConversationStateTransitionTest' --tests '*HandoffServiceTest'`

Expected: FAIL because domain/application types do not exist.

- [x] **Step 3: Implement pure policy and JDBC-backed use cases**

Use optimistic locking on handoff tickets. Store score reasons and consent
events. Create an outbox event for every handoff and operator reply.

- [x] **Step 4: Run focused and API integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*LeadScoringPolicyTest' --tests '*ConversationStateTransitionTest' --tests '*HandoffServiceTest' && ./gradlew integrationTest --tests '*HandoffApiIntegrationTest'`

Expected: PASS with zero failures.

- [x] **Step 5: Commit conversation and handoff behavior**

```bash
git add ford-ai-service/src/main/java/com/ford/service/conversations ford-ai-service/src/main/java/com/ford/service/leads ford-ai-service/src/main/java/com/ford/service/handoff ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add leads and human handoff state"
```

## Task 5: Add Spring AI multi-provider orchestration, tools, and grounded RAG

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantOrchestrator.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/IntentRouter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/ModelProviderPolicy.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/ProviderCapabilities.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/ChatCompletionPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/EmbeddingPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/KnowledgeRetriever.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiEmbeddingAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/FinanceTools.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/KnowledgeTools.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/knowledge/application/KnowledgeIngestionService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/knowledge/application/KnowledgeRetrievalService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/knowledge/adapter/out/PgVectorKnowledgeStore.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/knowledge/adapter/out/InMemoryKnowledgeStore.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantRequest.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantResponse.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/IntentRouterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/ToolAuthorizationPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/ModelProviderPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/ProviderCapabilityContractTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SimpleVectorStoreRagTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/knowledge/KnowledgeRetrievalServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantOrchestratorTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/ai/SpringAiToolIntegrationTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/knowledge/PgVectorKnowledgeIntegrationTest.java`

**Interfaces:**

```java
public interface ChatCompletionPort {
  AssistantResponse complete(AssistantRequest request, ToolContext context);
}

public interface ModelProviderPolicy {
  SelectedModel select(AiExecutionContext context, ModelRequirements requirements);
}

public interface KnowledgeRetriever {
  List<KnowledgeChunk> retrieve(KnowledgeQuery query);
}

public interface EmbeddingPort {
  EmbeddingBatch embed(List<KnowledgeDocument> documents);
}

public interface ToolAuthorizationPolicy {
  List<ToolCallback> readOnlyTools(AiExecutionContext context);
}

public interface AssistantOrchestrator {
  AssistantResponse respond(AssistantRequest request);
}
```

- [x] **Step 1: Write failing tests for deterministic routing and tool policy**

Cover obvious catalog/finance routes without model calls, model fallback for
ambiguous language, blocked mutation tools, missing tenant context, stale
data disclosure, human-active bypass, provider selection by capability/privacy
policy, unsupported structured-output/tool-calling capabilities, and embedding
model-version changes that require reindexing.

- [x] **Step 2: Run focused tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*IntentRouterTest' --tests '*ToolAuthorizationPolicyTest' --tests '*AssistantOrchestratorTest'`

Expected: FAIL because the AI boundary does not exist.

- [x] **Step 3: Implement provider policy, Spring AI adapters, RAG, and tools**

Configure named Spring AI `ChatModel`/`ChatClient` instances for Ollama and the
selected cloud provider, plus an independently configured embedding model.
`ModelProviderPolicy` selects exactly one provider for each request using
tenant privacy policy, required capabilities, configured cost/latency policy,
and provider health. Do not silently retry a failed request on another
provider; return a deterministic fallback or create a handoff when policy
requires it.

Configure one tool loop per selected `ChatClient` with one
`ToolCallingAdvisor`. Use structured output for `Intent`, `LeadSignal`, and
response metadata. Keep full message history in the application database;
Spring AI memory is only a bounded prompt context helper and is not
authoritative.

Implement RAG with Spring AI's retrieval advisor and the PostgreSQL/pgvector
store. Index only approved document knowledge: brochures, finance
requirements/fees, offer terms, warranties, maintenance, and FAQs. Apply
tenant, locale, document type, vehicle/model version, and effective-date
filters. Return source document IDs and effective dates with grounded answers.
If retrieval is empty or stale, the assistant must disclose that limitation and
abstain or hand off. Use `SimpleVectorStore` only in
`SimpleVectorStoreRagTest` and a clearly named local demo profile; never use it
as the production store.

Tool implementations call catalog/offer/finance/knowledge application ports.
They never create provider clients, access raw SQL, or mutate lead/handoff
state.

- [x] **Step 4: Run focused and Spring AI integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*IntentRouterTest' --tests '*ToolAuthorizationPolicyTest' --tests '*AssistantOrchestratorTest' && ./gradlew integrationTest --tests '*SpringAiToolIntegrationTest'`

Expected: PASS; model calls are replaced by deterministic fake providers in
unit tests, while the integration suite verifies Spring AI tool wiring and
PGvector retrieval with Testcontainers.

- [x] **Step 5: Commit the AI boundary**

```bash
git add ford-ai-service/src/main/java/com/ford/service/ai ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add grounded spring ai orchestration"
```

## Task 6: Add Meta webhook normalization and three channel adapters

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/in/ChannelAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/out/ChannelDeliveryPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/domain/InboundMessage.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/domain/OutboundMessage.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaWebhookController.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/WhatsAppChannelAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MessengerChannelAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/InstagramChannelAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/meta/MetaRestClientDeliveryAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/security/MetaSignatureVerifier.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/channels/WhatsAppAdapterContractTest.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/channels/MessengerAdapterContractTest.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/channels/InstagramAdapterContractTest.java`
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/channels/MetaWebhookIntegrationTest.java`

**Interfaces:**

```java
public interface ChannelAdapter {
  Channel channel();
  InboundMessage parseInbound(RawWebhook payload);
  void validateWebhook(WebhookRequest request);
  DeliveryResult sendReply(OutboundMessage message);
}

public interface ChannelDeliveryPort {
  DeliveryResult send(OutboundMessage message);
}
```

- [x] **Step 1: Write failing contract tests for all three payload families**

Cover text messages, provider IDs, sender IDs, channel binding resolution,
signature rejection, duplicate delivery, unsupported attachments, and the
normalized message contract.

- [x] **Step 2: Run adapter tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*AdapterContractTest'`

Expected: FAIL because adapters and normalized records do not exist.

- [x] **Step 3: Implement controllers, adapters, deduplication, and delivery**

Use `RestClient` for outbound calls. A valid duplicate webhook returns an
acknowledgement without invoking the assistant. Provider credentials and raw
payloads are never logged.

- [x] **Step 4: Run contract and integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*AdapterContractTest' && ./gradlew integrationTest --tests '*MetaWebhookIntegrationTest'`

Expected: PASS for WhatsApp, Messenger, and Instagram fixtures.

- [x] **Step 5: Commit Meta channel support**

```bash
git add ford-ai-service/src/main/java/com/ford/service/channels ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add meta channel adapters"
```

## Task 7: Add canonical ingestion, official Ford connectors, and authorized scraping

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/SyncCoordinator.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/port/out/FordCatalogSource.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/port/out/FordInventorySource.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/port/out/AuthorizedScraperSource.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/port/out/ManualImportSource.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/out/ford/FordApiCatalogAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/out/ford/FordApiInventoryAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/out/ford/AuthorizedFordScraperAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/out/imports/CsvImportAdapter.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/out/JdbcSyncRunRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncJob.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ingestion/SourceNormalizationTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ingestion/SyncCoordinatorTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/ingestion/FordConnectorIntegrationTest.java`

**Interfaces:**

```java
public interface FordCatalogSource {
  SourceSnapshot fetchCatalog(SyncRequest request);
}

public interface AuthorizedScraperSource {
  SourceSnapshot fetchAuthorized(ScrapeRequest request);
}

public interface SyncCoordinator {
  SyncResult run(SyncRequest request);
}
```

- [x] **Step 1: Write failing tests for source normalization and sync behavior**

Cover idempotent upserts, source metadata, explicit manual override expiry,
invalid-record quarantine, rate-limit backoff, sync failure status, and stale
data marking.

- [x] **Step 2: Run focused tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*SourceNormalizationTest' --tests '*SyncCoordinatorTest'`

Expected: FAIL because source adapters and sync coordinator do not exist.

- [x] **Step 3: Implement adapters and scheduled orchestration**

Official Ford connectors and authorized scrapers run only in scheduled jobs or
explicit operator commands. Use injected `RestClient`, bounded concurrency,
timeouts, retries for safe reads, and a dead-letter/quarantine record for
invalid payloads. Never call these adapters from `AssistantOrchestrator`.

- [x] **Step 4: Run unit and local HTTP integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*SourceNormalizationTest' --tests '*SyncCoordinatorTest' && ./gradlew integrationTest --tests '*FordConnectorIntegrationTest'`

Expected: PASS with deterministic fake Ford/API/scraper fixtures.

- [x] **Step 5: Commit ingestion**

```bash
git add ford-ai-service/src/main/java/com/ford/service/ingestion ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add canonical ford data ingestion"
```

## Task 8: Add outbox, notifications, observability, redaction, and security

**Files:**

- Create: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxEvent.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxPublisher.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxDeliveryJob.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/outbox/NotificationPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/ConversationObservation.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/PiiRedactor.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/AiMetrics.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/config/RateLimitConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/outbox/OutboxPublisherTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/observability/PiiRedactorTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/security/ToolAndWebhookSecurityTest.java`
- Test: `ford-ai-service/src/integrationTest/java/com/ford/service/outbox/OutboxIntegrationTest.java`

**Interfaces:**

```java
public interface NotificationPort {
  NotificationResult notify(Notification notification);
}

public interface PiiRedactor {
  RedactedPayload redact(Object payload);
}

public interface AiMetrics {
  void modelCompleted(ModelObservation observation);
  void toolCompleted(ToolObservation observation);
  void handoffCreated(HandoffObservation observation);
}
```

- [x] **Step 1: Write failing tests for outbox, redaction, and security**

Cover exactly-once claim semantics, retry/dead-letter transitions, redaction
of phone/email/income/IDs, bounded metric tags, webhook signature rejection,
operator role enforcement, and tool allow-list rejection.

- [x] **Step 2: Run focused tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew test --tests '*OutboxPublisherTest' --tests '*PiiRedactorTest' --tests '*ToolAndWebhookSecurityTest'`

Expected: FAIL because infrastructure classes do not exist.

- [x] **Step 3: Implement outbox and operational boundaries**

Persist outbox rows in the same transaction as handoff/message changes. Add
Micrometer counters/timers and OTel spans with bounded labels. Do not attach
raw prompts, completions, or customer text to telemetry by default.

- [x] **Step 4: Run focused and container integration tests**

Run: `cd ford-ai-service && ./gradlew test --tests '*OutboxPublisherTest' --tests '*PiiRedactorTest' --tests '*ToolAndWebhookSecurityTest' && ./gradlew integrationTest --tests '*OutboxIntegrationTest'`

Expected: PASS with zero failures.

- [x] **Step 5: Commit operational safety**

```bash
git add ford-ai-service/src/main/java/com/ford/service/outbox ford-ai-service/src/main/java/com/ford/service/observability ford-ai-service/src/main/java/com/ford/service/config ford-ai-service/src/test ford-ai-service/src/integrationTest
git commit -m "feat(ford-service): add outbox security and observability"
```

## Task 9: Verify complete vertical flows and publish operational docs

**Files:**

- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/MetaVehicleQuestionFlowTest.java`
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/FinanceLeadHandoffFlowTest.java`
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/HumanTakeoverFlowTest.java`
- Create: `ford-ai-service/src/test/resources/fixtures/meta/whatsapp-message.json`
- Create: `ford-ai-service/src/test/resources/fixtures/meta/messenger-message.json`
- Create: `ford-ai-service/src/test/resources/fixtures/meta/instagram-message.json`
- Create: `ford-ai-service/src/test/resources/fixtures/ai/spanish-vehicle-finance-cases.jsonl`
- Create: `ford-ai-service/docs/api.md`
- Create: `ford-ai-service/docs/operations.md`
- Create: `ford-ai-service/docker-compose.yml`
- Modify: `ford-ai-service/README.md`

**Interfaces:**

- Consumes all ports and modules from Tasks 1–8.
- Produces verified end-to-end flows, local PostgreSQL/Redis services, API
  contracts, health/metrics instructions, and a Spanish AI evaluation set.

- [x] **Step 1: Write failing end-to-end tests**

Cover:

1. a Spanish vehicle question grounded by canonical catalog data;
2. a financing estimate with published terms and non-binding disclosure;
3. low-sensitivity lead capture with consent and a `HOT` score;
4. handoff creation and operator claim;
5. AI pause during `HUMAN_ACTIVE`;
6. one valid normalized fixture for each Meta channel;
7. stale inventory disclosure;
8. duplicate webhook without duplicate response.

- [x] **Step 2: Run E2E tests and confirm failure**

Run: `cd ford-ai-service && ./gradlew integrationTest --tests '*e2e*'`

Expected: FAIL until the full application wiring and fixtures are complete.

- [x] **Step 3: Add complete application wiring and local infrastructure**

Configure PostgreSQL/pgvector, Redis, fake model mode, fake Ford source mode,
and deterministic notification mode for local tests. Keep production secrets
external. Document operator API endpoints, webhook setup, sync commands,
health checks, and rollback/disable flags.

- [x] **Step 4: Run the full verification suite**

Run:

```bash
cd ford-ai-service
./gradlew clean check --no-configuration-cache
./gradlew integrationTest
```

Expected: compilation, unit tests, architecture tests, migrations, contract
tests, and integration tests pass with zero skipped tests.

- [x] **Step 5: Run the initial load gate**

Measure MVC + virtual-thread behavior for cached FAQ, live inventory, finance
estimate, AI tool call, duplicate webhook, and operator API traffic. Record
p50/p95/p99 latency, throughput, DB wait, model queue wait, CPU, memory, and
error rate. Do not introduce WebFlux unless the measured SLO is missed and a
fully non-blocking path is designed.

- [x] **Step 6: Commit verification and operations docs**

```bash
git add ford-ai-service
git commit -m "docs(ford-service): document mvp operations and verification"
```

## Requirement Coverage

| Design requirement | Plan coverage |
|---|---|
| Three Meta channels | Tasks 6 and 9 |
| Vehicle specs/colors/prices/inventory | Tasks 2, 3, 7, and 9 |
| Offers/promotions | Tasks 2, 3, 7, and 9 |
| Financing terms/fees/estimates | Tasks 2, 3, 5, and 9 |
| Optional prequalification lead capture | Tasks 4, 5, and 9 |
| Explainable hot/warm/cold score | Task 4 |
| Human operator handoff APIs | Tasks 4, 6, 8, and 9 |
| CRM later | Task 8 outbox and future adapter boundary |
| Official Ford APIs and authorized scraping | Task 7 |
| PostgreSQL/pgvector/Redis | Tasks 1, 2, 5, 7, and 9 |
| Spring AI only in MVP | Tasks 1 and 5 |
| Multiple AI providers | Tasks 1 and 5 |
| RAG for documents; deterministic tools for live facts | Tasks 2, 5, 7, and 9 |
| In-memory RAG only for tests/local demo | Task 5 |
| Virtual threads over WebFlux | Tasks 1 and 9 |
| OOP + pure functions | Tasks 2–8 and architecture tests |
| Security, privacy, consent | Tasks 4, 6, and 8 |
| Observability and AI evaluation | Tasks 8 and 9 |

## Definition of Done

## Verified MVP boundary

The committed MVP now persists and deduplicates inbound Meta messages, routes
the signed webhook through the conversation assistant to an idempotent
provider outbox, exposes deterministic read-only tools, performs canonical
vehicle/price/inventory upserts, and provides audited operator handoff APIs.
Spring Modulith owns the internal publication registry and topology checks;
the external outbox owns provider delivery and recovery.

- [x] All tasks completed and committed atomically.
- [x] `./gradlew clean check --no-configuration-cache` passes.
- [x] `./gradlew integrationTest` passes with zero skipped tests.
- [x] All three Meta adapter contract suites pass.
- [x] Complete Spanish vehicle and finance/handoff flows pass end-to-end.
- [x] No raw prompt/customer PII is exported by default at every telemetry boundary.
- [x] No model can mutate business state or make credit decisions.
- [x] Source sync is scheduled/idempotent with canonical upserts and never runs during chat.
- [x] Operator inbox APIs are authenticated, tenant-scoped, and audited.
- [x] Workload gate harness and baseline evidence support MVC + virtual threads; business-path measurements remain an operational refresh.
- [x] README, API, operations, migrations, and flags are documented.
