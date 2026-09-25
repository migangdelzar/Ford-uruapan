# Jev Classification Orchestration Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

Goal: Add active TypeSafe Jev classification to each eligible conversation turn, pass the validated decision bundle into response generation, and persist classifier evidence without allowing AI output to bypass application policy.

Architecture: The application creates one ConversationDecision from a TypeSafe systemOne call containing atomic Noul, Choice, and Score questions for intent, lead qualification, handoff signals, tool family, and sensitive data. A deterministic adapter remains the fallback. The decision is attached to the existing assistant request, rendered as sanitized context for the normal OpenRouter/free-model chat provider, and persisted as normalized classifier rows.

Tech Stack: Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, Spring AI TypeSafe Community 0.1.0, Spring Modulith events, Spring JDBC JdbcClient, Liquibase, PostgreSQL, JUnit 5, Mockito, H2, Testcontainers.

## Global Constraints

- Use org.springaicommunity:spring-ai-starter-typesafe:0.1.0 and org.springaicommunity:typesafe-spring-ai:0.1.0.
- Use the official TypeSafeClient; do not implement Jev HTTP or route Jev through OpenRouter.
- Keep OpenRouter only in the existing OpenAI-compatible response-generation adapter.
- Make one TypeSafe decision request per eligible turn containing all required atomic questions.
- Validate all Jev labels and confidence values before using them.
- Jev recommendations never authorize tools, create handoffs, mutate state, or send messages.
- FORD_HUMAN_HANDOFF_ENABLED=false must prevent handoff even when Jev recommends it.
- Persist normalized decision evidence only; never persist raw Jev prompts or provider responses.
- Classifier and provider failures must not prevent durable inbound-message persistence.
- Keep IntentRouter as the deterministic fallback until the Jev path is proven stable.
- Use TDD: write the focused failing test, run it, implement the smallest passing change, then run regression tests.
- Do not use a live TYPESAFE_API_KEY in ordinary tests; provider tests use mocks and sanitized fixtures.

## File Map

Create:

- ford-ai-service/src/main/java/com/ford/service/ai/application/ClassificationContext.java — immutable classifier input.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ClassifierType.java — persisted classifier categories.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ConversationIntent.java — Jev-facing intent vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/LeadTemperature.java — ordered lead-temperature vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/LeadStage.java — lead lifecycle vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/HandoffRecommendation.java — handoff recommendation vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ToolFamily.java — tool-family vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/SensitiveDataLevel.java — sensitive-data vocabulary.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ClassificationEvidence.java — normalized evidence metadata.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ConversationDecision.java — complete validated decision bundle.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ConversationDecisionPort.java — application port for one decision request.
- ford-ai-service/src/main/java/com/ford/service/ai/application/ConversationDecisionService.java — primary/fallback coordination.
- ford-ai-service/src/main/java/com/ford/service/ai/application/DecisionPolicy.java — final action policy.
- ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/rules/RulesConversationDecisionAdapter.java — deterministic fallback.
- ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/typesafe/TypeSafeConversationDecisionAdapter.java — TypeSafeClient adapter.
- ford-ai-service/src/main/java/com/ford/service/ai/configuration/ClassificationProperties.java — ford.ai.classification configuration.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationDecisionRepository.java — persistence port.
- ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationDecisionRepository.java — PostgreSQL adapter.
- ford-ai-service/src/main/resources/db/changelog/changes/008-conversation-decisions.yaml — decision audit schema.

Modify:

- ford-ai-service/gradle/libs.versions.toml and ford-ai-service/build.gradle.kts — TypeSafe dependencies.
- ford-ai-service/src/main/resources/application.yml and ford-ai-service/.env.example — active Jev/OpenRouter configuration.
- AssistantRequest, AssistantTurn, SpringAiAssistantService, DefaultAssistantOrchestrator, SpringAiChatCompletionAdapter, SpringAiConfiguration, ToolAuthorizationPolicy.
- AssistantReply, ConversationAssistantAdapter, ConversationConfiguration, ConversationWorkflow.
- db.changelog-master.yaml.
- CapabilityDiagnostics and ObservabilityConfiguration.
- Existing AI, conversation, configuration, architecture, and Liquibase tests.

---

### Task 1: Add TypeSafe dependencies and validated configuration

Files:

- Modify: ford-ai-service/gradle/libs.versions.toml
- Modify: ford-ai-service/build.gradle.kts
- Create: ford-ai-service/src/main/java/com/ford/service/ai/configuration/ClassificationProperties.java
- Modify: ford-ai-service/src/main/resources/application.yml
- Modify: ford-ai-service/src/main/resources/application-dev.yml
- Modify: ford-ai-service/src/main/resources/application-prod.yml
- Modify: ford-ai-service/.env.example
- Test: ford-ai-service/src/test/java/com/ford/service/ai/ClassificationPropertiesTest.java

Interfaces:

- ClassificationProperties exposes enabled, mode, provider, timeoutMs, minConfidence, fallback, piiPolicy, five classifier flags, guardrailsEnabled, selfRefineEnabled, and selfRefineMaxAttempts.
- Spring property spring.ai.typesafe.api-key is bound from TYPESAFE_API_KEY.

- [ ] Step 1: Write the failing properties test.

Bind these exact values and assert them:

    ford.ai.classification.enabled=true
    ford.ai.classification.mode=active
    ford.ai.classification.provider=typesafe
    ford.ai.classification.timeout-ms=1500
    ford.ai.classification.min-confidence=0.70
    ford.ai.classification.fallback=rules
    ford.ai.classification.pii-policy=redact
    ford.ai.classification.intent-enabled=true
    ford.ai.classification.lead-enabled=true
    ford.ai.classification.handoff-enabled=true
    ford.ai.classification.tools-enabled=true
    ford.ai.classification.sensitive-data-enabled=true

The test must assert enabled=true, mode=active, provider=typesafe, timeoutMs=1500, minConfidence=0.70, fallback=rules, piiPolicy=redact, and allClassifiersEnabled()=true.

- [ ] Step 2: Run the focused test to verify it fails.

    cd ford-ai-service
    ./gradlew test --tests com.ford.service.ai.ClassificationPropertiesTest

Expected: compilation or context failure because ClassificationProperties and TypeSafe configuration do not exist.

- [ ] Step 3: Add the dependency aliases.

Add version typesafe = "0.1.0" to gradle/libs.versions.toml and these libraries:

    spring-ai-starter-typesafe = org.springaicommunity:spring-ai-starter-typesafe:0.1.0
    typesafe-spring-ai = org.springaicommunity:typesafe-spring-ai:0.1.0

Add both as implementation dependencies in build.gradle.kts.

- [ ] Step 4: Implement ClassificationProperties.

Use @ConfigurationProperties(prefix = "ford.ai.classification") with defaults:

    enabled=true
    mode=active
    provider=typesafe
    timeoutMs=1500
    minConfidence=0.70
    fallback=rules
    piiPolicy=redact
    intentEnabled=true
    leadEnabled=true
    handoffEnabled=true
    toolsEnabled=true
    sensitiveDataEnabled=true
    guardrailsEnabled=false
    selfRefineEnabled=false
    selfRefineMaxAttempts=1

Reject non-positive timeout/max-attempt values and confidence outside [0.0, 1.0]. allClassifiersEnabled() returns true only when all five classifier flags are true.

- [ ] Step 5: Add YAML and environment bindings.

Add spring.ai.typesafe.api-key from TYPESAFE_API_KEY.

Add ford.ai.classification settings with these environment names and defaults:

    FORD_AI_CLASSIFICATION_ENABLED=true
    FORD_AI_CLASSIFICATION_MODE=active
    FORD_AI_CLASSIFICATION_PROVIDER=typesafe
    FORD_AI_CLASSIFICATION_TIMEOUT_MS=1500
    FORD_AI_CLASSIFICATION_MIN_CONFIDENCE=0.70
    FORD_AI_CLASSIFICATION_FALLBACK=rules
    FORD_AI_CLASSIFICATION_PII_POLICY=redact
    FORD_AI_CLASSIFIER_INTENT_ENABLED=true
    FORD_AI_CLASSIFIER_LEAD_ENABLED=true
    FORD_AI_CLASSIFIER_HANDOFF_ENABLED=true
    FORD_AI_CLASSIFIER_TOOLS_ENABLED=true
    FORD_AI_CLASSIFIER_SENSITIVE_DATA_ENABLED=true
    FORD_AI_GUARDRAILS_ENABLED=false
    FORD_AI_SELF_REFINE_ENABLED=false
    FORD_AI_SELF_REFINE_MAX_ATTEMPTS=1

Add TYPESAFE_API_KEY=replace-with-secret to .env.example and never copy a real credential.

Register ClassificationProperties with @EnableConfigurationProperties in SpringAiConfiguration alongside the existing AI composition beans.

- [ ] Step 6: Run the focused test and dependency resolution.

    ./gradlew test --tests com.ford.service.ai.ClassificationPropertiesTest
    ./gradlew dependencies --configuration runtimeClasspath | rg 'spring-ai-starter-typesafe|typesafe-spring-ai'

Expected: the properties test passes and both org.springaicommunity artifacts resolve at 0.1.0.

- [ ] Step 7: Commit.

    git add gradle/libs.versions.toml build.gradle.kts src/main/java/com/ford/service/ai/configuration/ClassificationProperties.java src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-prod.yml .env.example src/test/java/com/ford/service/ai/ClassificationPropertiesTest.java
    git commit -m "feat: configure TypeSafe classification provider"

---

### Task 2: Define typed decisions and policy vocabulary

Files:

- Create the typed records and enums under ford-ai-service/src/main/java/com/ford/service/ai/application/.
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/Intent.java
- Test: ford-ai-service/src/test/java/com/ford/service/ai/ConversationDecisionTest.java
- Test: ford-ai-service/src/test/java/com/ford/service/ai/DecisionPolicyTest.java

Interfaces:

    public record ClassificationContext(
            UUID tenantId,
            UUID conversationId,
            String message,
            String memoryContext,
            String conversationState,
            boolean containsSensitiveData) {}

    public record ClassificationEvidence(
            ClassifierType classifier,
            String classification,
            double confidence,
            String reasonCode,
            String provider,
            String model,
            long latencyMs,
            boolean fallbackUsed) {}

    public record ConversationDecision(
            ConversationIntent intent,
            LeadTemperature leadTemperature,
            LeadStage leadStage,
            HandoffRecommendation handoffRecommendation,
            ToolFamily toolFamily,
            SensitiveDataLevel sensitiveDataLevel,
            List<ClassificationEvidence> evidence) {}

Intent keeps existing values for compatibility. Add a mapper from VEHICLE_DETAILS to Intent.CATALOG and HUMAN_REQUEST to Intent.LEAD_HANDOFF.

- [ ] Step 1: Write tests for decision immutability and legacy mapping.

Test null decision fields fail, evidence is copied, VEHICLE_DETAILS maps to CATALOG, HUMAN_REQUEST maps to LEAD_HANDOFF, and toPromptContext() has a stable field order with no provider metadata.

- [ ] Step 2: Run the focused tests to verify failure.

    ./gradlew test --tests com.ford.service.ai.ConversationDecisionTest --tests com.ford.service.ai.DecisionPolicyTest

Expected: compilation failure because the typed decision classes do not exist.

- [ ] Step 3: Add exact enum values.

    ClassifierType: INTENT, LEAD, HANDOFF, TOOL_FAMILY, SENSITIVE_DATA
    ConversationIntent: GENERAL, INVENTORY, VEHICLE_DETAILS, FINANCE, OFFERS, APPOINTMENT, KNOWLEDGE, HUMAN_REQUEST
    LeadTemperature: UNKNOWN, COLD, WARM, HOT
    LeadStage: NEW, QUALIFYING, QUALIFIED, CONVERTED, LOST
    HandoffRecommendation: NONE, USER_REQUESTED, NEGATIVE_SENTIMENT, LOW_CONFIDENCE, COMPLEX_REQUEST
    ToolFamily: NONE, VEHICLE_SEARCH, VEHICLE_DETAILS, FINANCE_ESTIMATE, KNOWLEDGE_SEARCH, APPOINTMENT
    SensitiveDataLevel: NONE, POSSIBLE, CONFIRMED

Implement ConversationDecision.toLegacyIntent() and ConversationDecision.toPromptContext().

- [ ] Step 4: Implement DecisionPolicy.

Expose:

    boolean mayCreateHandoff(ConversationDecision decision, boolean handoffEnabled)
    Set<String> allowedToolNames(ConversationDecision decision, Set<String> availableTools)
    boolean shouldUseRestrictedProvider(ConversationDecision decision)

mayCreateHandoff returns false when handoff is disabled or the recommendation is NONE. allowedToolNames returns an empty set for ToolFamily.NONE and never returns a name outside availableTools. shouldUseRestrictedProvider returns true for CONFIRMED sensitive data.

- [ ] Step 5: Run tests and commit.

    ./gradlew test --tests com.ford.service.ai.ConversationDecisionTest --tests com.ford.service.ai.DecisionPolicyTest
    git add src/main/java/com/ford/service/ai/application src/test/java/com/ford/service/ai/ConversationDecisionTest.java src/test/java/com/ford/service/ai/DecisionPolicyTest.java
    git commit -m "feat: add typed conversation decisions"

---

### Task 3: Implement TypeSafe and deterministic decision adapters

Files:

- Create ConversationDecisionPort and ConversationDecisionService under ai/application.
- Create RulesConversationDecisionAdapter under ai/adapter/out/rules.
- Create TypeSafeConversationDecisionAdapter under ai/adapter/out/typesafe.
- Test RulesConversationDecisionAdapterTest, TypeSafeConversationDecisionAdapterTest, and ConversationDecisionServiceTest.

Interfaces:

    public interface ConversationDecisionPort {
        ConversationDecision decide(ClassificationContext context);
    }

    public final class ConversationDecisionService {
        public ConversationDecision decide(ClassificationContext context);
    }

- [ ] Step 1: Write fallback tests.

Cover:

    ¿Tienen Ranger disponible? -> INVENTORY / VEHICLE_SEARCH
    Quiero una mensualidad -> FINANCE / FINANCE_ESTIMATE
    Quiero hablar con un asesor -> HUMAN_REQUEST / USER_REQUESTED
    Hola -> GENERAL / NONE
    blank input -> GENERAL / NONE

Also assert service fallback evidence has fallbackUsed=true when TypeSafe throws.

- [ ] Step 2: Write TypeSafe fixture tests.

Mock TypeSafeClient.systemOne and return sanitized answers:

    intent=FINANCE, confidence=0.91
    lead_stage=QUALIFYING, confidence=0.84
    lead_temperature score=2.0, confidence=0.79
    user_requests_human=0.04
    negative_sentiment=0.03
    complex_request=0.12
    tool_family=FINANCE_ESTIMATE, confidence=0.88
    has_sensitive_data=0.02

Assert the mapped bundle and evidence provider=typesafe.

- [ ] Step 3: Run tests to verify failure.

    ./gradlew test --tests com.ford.service.ai.RulesConversationDecisionAdapterTest --tests com.ford.service.ai.TypeSafeConversationDecisionAdapterTest --tests com.ford.service.ai.ConversationDecisionServiceTest

Expected: compilation failure because the port and adapters do not exist.

- [ ] Step 4: Implement RulesConversationDecisionAdapter.

Delegate intent detection to IntentRouter. Map explicit human phrases to USER_REQUESTED. Infer tool family only for existing read-only tools. Set unknown lead values. Set sensitive data CONFIRMED only for explicit credential/payment-secret requests. Use provider=rules, model=deterministic, and fallbackUsed=true for evidence.

- [ ] Step 5: Implement the TypeSafe question map.

Call TypeSafeClient.systemOne once with these keys:

    intent
    lead_stage
    lead_temperature
    user_requests_human
    negative_sentiment
    complex_request
    tool_family
    has_sensitive_data

Use Choice for intent, lead_stage, and tool_family. Use Score with ordered levels UNKNOWN, COLD, WARM, HOT for lead_temperature. Use Noul for the four boolean signals. Include NONE as a tool option and independently ask whether any tool applies because Choice always selects a winner.

Map ChoiceAnswer.confidence(), ScoreAnswer.confidence(), and Noul probability into evidence. Unknown labels and TypeSafe typed exceptions must trigger service fallback.

- [ ] Step 6: Implement primary/fallback coordination.

ConversationDecisionService.decide must:

1. Return rules when classification is disabled.
2. Call TypeSafe when enabled and a TypeSafeClient exists.
3. Return rules in shadow mode while logging a comparison outcome.
4. Replace only low-confidence Choice/Score values with deterministic values.
5. Use the restrictive fallback for sensitive-data failures.
6. Never throw provider failure back to inbound-message processing.

- [ ] Step 7: Run tests and commit.

    ./gradlew test --tests com.ford.service.ai.RulesConversationDecisionAdapterTest --tests com.ford.service.ai.TypeSafeConversationDecisionAdapterTest --tests com.ford.service.ai.ConversationDecisionServiceTest
    git add src/main/java/com/ford/service/ai/application/ConversationDecisionPort.java src/main/java/com/ford/service/ai/application/ConversationDecisionService.java src/main/java/com/ford/service/ai/adapter/out/rules src/main/java/com/ford/service/ai/adapter/out/typesafe src/test/java/com/ford/service/ai/RulesConversationDecisionAdapterTest.java src/test/java/com/ford/service/ai/TypeSafeConversationDecisionAdapterTest.java src/test/java/com/ford/service/ai/ConversationDecisionServiceTest.java
    git commit -m "feat: add TypeSafe conversation classification"

---

### Task 4: Attach decisions to assistant generation

Files:

- Modify: ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantRequest.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantTurn.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java
- Test AssistantOrchestratorTest, SpringAiAssistantServiceTest, and SpringAiChatCompletionAdapterTest.
- Test: ford-ai-service/src/test/java/com/ford/service/ai/ToolAuthorizationPolicyTest.java

Interfaces:

- AssistantRequest carries a non-null ConversationDecision decision.
- AssistantTurn carries the same decision.
- Existing constructors remain available and use a deterministic default decision.

- [ ] Step 1: Write propagation tests.

Assert SpringAiAssistantService calls ConversationDecisionService before the orchestrator and returns the same decision in AssistantTurn. Assert a FINANCE decision reaches AssistantRequest.

- [ ] Step 2: Run tests to verify failure.

    ./gradlew test --tests com.ford.service.ai.AssistantOrchestratorTest --tests com.ford.service.ai.SpringAiAssistantServiceTest

Expected: compilation failure because decision fields do not exist.

- [ ] Step 3: Add backward-compatible decision fields.

Keep these constructors working:

    new AssistantRequest(tenantId, conversationId, message, state, containsSensitiveData)
    new AssistantTurn(text, intent, requestsHuman, sourceIds, provider, modelVersion)

Those constructors use a deterministic default decision.

- [ ] Step 4: Classify once in SpringAiAssistantService.

Build ClassificationContext from AiExecutionContext and userText. Pass the decision into AssistantRequest. Use decision.toLegacyIntent() for AssistantTurn.intent. Set requestsHuman from the recommendation; ConversationWorkflow still applies HumanHandoffPolicy.

When generation fails, return the safe fallback response together with the already-computed decision.

- [ ] Step 5: Add sanitized decision context to the Spring AI system prompt.

Append these fields in stable order:

    [DECISION CONTEXT]
    intent=...
    lead_stage=...
    lead_temperature=...
    tool_family=...
    handoff=...
    sensitive_data=...
    [/DECISION CONTEXT]

Escape memory delimiters exactly as the existing safe() method does. Do not send probabilities, request IDs, raw Jev explanations, or API metadata to the generation model.

- [ ] Step 6: Keep tool authorization final.

Add ToolAuthorizationPolicy.readOnlyTools(AiExecutionContext context, Set<String> allowedNames). It must first apply the existing denied-term check, then retain only callbacks whose ToolDefinition.name() is in allowedNames. A FINANCE_ESTIMATE decision cannot expose a tool not already returned by ToolAuthorizationPolicy.

- [ ] Step 7: Run tests and commit.

    ./gradlew test --tests com.ford.service.ai.AssistantOrchestratorTest --tests com.ford.service.ai.SpringAiAssistantServiceTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest --tests com.ford.service.ai.ToolAuthorizationPolicyTest
    git add src/main/java/com/ford/service/ai/api/AssistantRequest.java src/main/java/com/ford/service/ai/api/AssistantTurn.java src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java src/test/java/com/ford/service/ai
    git commit -m "feat: pass classified decisions to response generation"

---

### Task 5: Persist normalized classifier evidence

Files:

- Modify db.changelog-master.yaml.
- Create 008-conversation-decisions.yaml.
- Create ConversationDecisionRepository and JdbcConversationDecisionRepository.
- Modify AssistantReply, ConversationAssistantAdapter, ConversationConfiguration, and ConversationWorkflow.
- Test JdbcConversationDecisionRepositoryTest, ConversationWorkflowTest, and LiquibaseStartupTest.

Interface:

    public interface ConversationDecisionRepository {
        void save(UUID conversationId, String providerMessageId, ConversationDecision decision);
    }

Use providerMessageId as the idempotency key because the inbound event exposes it and current conversation_message uses it. Do not invent a foreign key to a message UUID unavailable to the webhook workflow.

- [ ] Step 1: Write the repository contract test.

Create an H2 schema with conversation and conversation_decision. Save a complete decision and assert five rows. Save it again with the same conversation/provider-message/classifier key and assert the row count remains five.

- [ ] Step 2: Run the focused test to verify failure.

    ./gradlew test --tests com.ford.service.conversations.JdbcConversationDecisionRepositoryTest

Expected: compilation failure because the repository and table do not exist.

- [ ] Step 3: Add Liquibase changeset 008.

Create conversation_decision with:

    id uuid primary key
    conversation_id uuid not null references conversation(id) on delete cascade
    provider_message_id varchar(240) not null
    classifier_type varchar(60) not null
    classification varchar(120) not null
    confidence numeric(8,6) not null
    reason_code varchar(120) not null
    provider varchar(80) not null
    model varchar(160) not null
    latency_ms bigint not null
    fallback_used boolean not null
    created_at timestamptz not null
    unique (conversation_id, provider_message_id, classifier_type)

Add an index on conversation_id and created_at descending. Add rollback that drops the index and table. Include the changeset after 007-conversation-memory.yaml.

- [ ] Step 4: Implement JdbcConversationDecisionRepository.

Insert one row per ClassificationEvidence using on conflict (conversation_id, provider_message_id, classifier_type) do update. Clamp confidence to [0.0, 1.0] before writing. Use Timestamp.from(Instant.now()) for created_at.

- [ ] Step 5: Propagate and save the decision.

Add ConversationDecision decision to AssistantReply with a backward-compatible constructor. ConversationAssistantAdapter copies it from AssistantTurn. Inject ConversationDecisionRepository into ConversationWorkflow and save immediately after assistant response and before completing the bot turn.

Catch repository failures, log only conversationId, providerMessageId, and exception type, and continue the response flow so audit persistence cannot drop a customer message.

- [ ] Step 6: Run repository, workflow, and Liquibase tests.

    ./gradlew test --tests com.ford.service.conversations.JdbcConversationDecisionRepositoryTest --tests com.ford.service.conversations.ConversationWorkflowTest --tests com.ford.service.LiquibaseStartupTest

Expected: all focused tests pass, including bot-only handoff behavior.

- [ ] Step 7: Commit.

    git add src/main/resources/db/changelog/db.changelog-master.yaml src/main/resources/db/changelog/changes/008-conversation-decisions.yaml src/main/java/com/ford/service/conversations src/test/java/com/ford/service/conversations src/test/java/com/ford/service/LiquibaseStartupTest.java
    git commit -m "feat: persist conversation classification evidence"

---

### Task 6: Wire TypeSafe, fallbacks, and startup diagnostics

Files:

- Modify SpringAiConfiguration.
- Modify CapabilityDiagnostics and ObservabilityConfiguration.
- Modify SpringAiConfigurationSliceTest and CapabilityDiagnosticsTest.

Register:

    @Bean
    ConversationDecisionPort conversationDecisionPort(
            ObjectProvider<TypeSafeClient> typeSafeClients,
            ClassificationProperties properties,
            IntentRouter intentRouter) {
        var rules = new RulesConversationDecisionAdapter(intentRouter);
        var client = typeSafeClients.getIfAvailable();
        return client == null || !properties.enabled()
                ? rules
                : new TypeSafeConversationDecisionAdapter(client, properties);
    }

Register ConversationDecisionService with the primary port, rules fallback, and ClassificationProperties.

- [ ] Step 1: Write a slice test with no TypeSafe credential.

Set spring.ai.typesafe.api-key= and ford.ai.classification.enabled=true. Assert the context contains ConversationDecisionService and the rules fallback. This verifies clean startup without a secret.

- [ ] Step 2: Run the slice test to verify failure.

    ./gradlew test --tests com.ford.service.ai.SpringAiConfigurationSliceTest

Expected: missing bean or missing configuration failure before wiring is added.

- [ ] Step 3: Wire the optional TypeSafe bean.

Use ObjectProvider<TypeSafeClient>. Keep the rules adapter available when the client is absent. Do not manually construct a live client in application code.

- [ ] Step 4: Extend capability diagnostics.

Add classification enabled/provider/mode while preserving existing constructors. The startup summary must include:

    classification=enabled
    classification.provider=typesafe
    classification.mode=active

Never log TYPESAFE_API_KEY, OpenRouter keys, request content, or provider responses.

- [ ] Step 5: Run tests and commit.

    ./gradlew test --tests com.ford.service.ai.SpringAiConfigurationSliceTest --tests com.ford.service.observability.CapabilityDiagnosticsTest
    git add src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java src/main/java/com/ford/service/observability/CapabilityDiagnostics.java src/main/java/com/ford/service/observability/ObservabilityConfiguration.java src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java src/test/java/com/ford/service/observability/CapabilityDiagnosticsTest.java
    git commit -m "feat: wire TypeSafe classification diagnostics"

---

### Task 7: Add optional TypeSafe guardrail and self-refine advisors

Files:

- Modify SpringAiConfiguration and SpringAiChatCompletionAdapter.
- Create TypeSafeAdvisorConfiguration.java.
- Test TypeSafeAdvisorConfigurationTest and SpringAiChatCompletionAdapterTest.

Interfaces:

    JevGuardrailAdvisor.builder(typeSafeClient).build()
    JevSelfRefineAdvisor.builder().judge(judge).maxRepeatAttempts(maxAttempts).build()

- [ ] Step 1: Write feature-flag tests.

Assert guardrails and self-refine are disabled by default. Assert enabling guardrails requires a TypeSafe client. Assert the configured self-refine attempt count is bounded.

- [ ] Step 2: Run the focused test to verify failure.

    ./gradlew test --tests com.ford.service.ai.TypeSafeAdvisorConfigurationTest

Expected: compilation failure because advisor configuration does not exist.

- [ ] Step 3: Add guarded advisor configuration.

Build a judge only for high-value generated responses. Use a groundedness Noul and completeness Score with explicit thresholds. Keep both features disabled by default. The guardrail runs after self-refinement and is the final safety decision.

Do not add JevToolIndex or JevDocumentReranker in this plan. The current tool catalog is small and the existing vector retrieval path needs its own quality baseline before adding per-document judgment calls.

- [ ] Step 4: Keep rollout conservative.

Do not enable self-refinement for every WhatsApp message. Enable guardrails only after the classifier path is verified. Enable self-refinement later for financing and quotation intents with maxRepeatAttempts=1.

- [ ] Step 5: Run tests and commit.

    ./gradlew test --tests com.ford.service.ai.TypeSafeAdvisorConfigurationTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest
    git add src/main/java/com/ford/service/ai/configuration/TypeSafeAdvisorConfiguration.java src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java src/test/java/com/ford/service/ai/TypeSafeAdvisorConfigurationTest.java src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java
    git commit -m "feat: add guarded TypeSafe response advisors"

---

### Task 8: Add end-to-end verification and operations documentation

Files:

- Modify ford-ai-service/src/test/resources/application-test.yml.
- Create ford-ai-service/src/test/java/com/ford/service/ai/TypeSafeConversationFlowIntegrationTest.java.
- Test: ford-ai-service/src/test/java/com/ford/service/architecture/ApplicationModulesTest.java; keep the new AI/conversation packages inside their existing module boundaries.
- Modify ford-ai-service/docs/operations.md.
- Do not modify ford-ai-service/docs/api.md; classifier decisions remain internal in this plan.

- [ ] Step 1: Write the end-to-end test.

Arrange an inbound finance message, a mocked TypeSafe response classified as FINANCE/WARM/FINANCE_ESTIMATE, and a generated Spanish response. Assert:

    inbound message is persisted
    one TypeSafe decision call occurs
    generation receives decision context
    five classifier evidence rows are persisted
    outbound turn is completed
    outbox delivery event is published
    no handoff ticket is created when human handoff is disabled

- [ ] Step 2: Add provider-failure cases.

Test a TypeSafe timeout and a free-model 429. Assert inbound persistence remains successful, rules fallback evidence is recorded, the safe response is produced, and the transaction is not left in PostgreSQL state 25P02.

- [ ] Step 3: Add duplicate delivery coverage.

Send the same provider message ID twice. Assert the decision repository unique key prevents duplicate classifier rows and existing inbound idempotency prevents duplicate outbound delivery.

- [ ] Step 4: Document deployment settings and checks.

Document these deployment settings:

    TYPESAFE_API_KEY
    FORD_AI_CLASSIFICATION_ENABLED=true
    FORD_AI_CLASSIFICATION_MODE=active
    FORD_AI_CLOUD_BASE_URL=https://openrouter.ai/api/v1
    FORD_AI_CLOUD_MODEL_VERSION=google/gemma-4-26b-a4b-it:free
    FORD_HUMAN_HANDOFF_ENABLED=false

Document this query:

    select classifier_type, classification, confidence, fallback_used, created_at
    from conversation_decision
    where conversation_id = :conversation_id
    order by created_at desc;

Document that free OpenRouter generation models may rate-limit and that classification falls back to rules without losing the message.

- [ ] Step 5: Run complete verification.

    ./gradlew test
    ./gradlew integrationTest
    ./gradlew check

Expected: unit tests, integration tests, Liquibase startup, Modulith architecture checks, and existing regression tests pass.

- [ ] Step 6: Inspect and commit.

    git diff --check
    git status --short
    git log -8 --oneline
    git add src test docs gradle build.gradle.kts
    git commit -m "test: verify TypeSafe classified conversation flow"

## Verification Checklist

- TypeSafeClient comes from Spring Boot auto-configuration and is optional when no key is configured.
- The active path makes one Jev call containing all classifier questions.
- Rules fallback is used per classifier when Jev is unavailable or inconclusive.
- Choice and Score confidence gates are enforced; Noul probabilities are thresholded directly.
- The generation model receives sanitized decision context.
- Tools remain constrained by ToolAuthorizationPolicy.
- Handoff remains disabled when FORD_HUMAN_HANDOFF_ENABLED=false.
- Decision evidence is durable and idempotent.
- Raw prompts, raw provider responses, and API keys are absent from logs and database rows.
- Provider failures do not abort webhook persistence or leave the transaction unusable.
- Free-model rate limits trigger fallback behavior rather than duplicate messages.
- ./gradlew check passes before claiming completion.
