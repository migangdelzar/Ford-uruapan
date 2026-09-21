# Deterministic Spring Configuration and Bot-Only Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Make every optional capability explicitly configurable, make the Spring bean graph deterministic, and support complete bot-only operation through FORD_HUMAN_HANDOFF_ENABLED=false.

**Architecture:** Replace order-sensitive application-level bean conditions with typed module properties, explicit composition configurations, and optional dependency providers. Always register the conversation workflow and an assistant service; use provider-specific Spring AI model ports when available and a deterministic unavailable-provider port otherwise. Enforce the handoff policy at both the assistant fallback boundary and the conversation workflow boundary.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Modulith events, Spring MVC slice tests, JUnit 5, Mockito, AssertJ, Gradle, mise.

## Global Constraints

- Use unit and Spring slice tests only; do not run or add integration tests.
- Preserve existing FORD_* environment variable names and webhook routes.
- Map FORD_HUMAN_HANDOFF_ENABLED to ford.conversations.human-handoff.enabled, defaulting to true.
- Never log prompts, completions, customer message content, access tokens, app secrets, authorization headers, or credential-bearing URLs.
- Do not add a database migration.
- Do not commit .env, .env.prod, provider credentials, or other secret values.
- Use ConditionalOnProperty for feature activation and avoid ConditionalOnBean in regular application composition configurations.
- End every task with focused verification and a focused commit.

## File map

Create or modify only these responsibilities:

- ford-ai-service/src/main/java/com/ford/service/ai/configuration/AiProperties.java: typed AI provider settings.
- ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java: provider descriptors and provider-specific model ports.
- ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java: deterministic AI composition root.
- ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/UnavailableChatCompletionPort.java: controlled no-provider behavior.
- ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java: safe provider-failure handling.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationProperties.java: typed conversation policy.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/HumanHandoffPolicy.java: handoff decision and fallback text.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java: typed property registration and workflow wiring.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java: direct assistant delegation.
- ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java: handoff policy enforcement.
- ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaChannelConfiguration.java: Boot-managed Jackson usage.
- ford-ai-service/src/main/java/com/ford/service/outbox/OutboxProperties.java: typed outbox timing and retry settings.
- ford-ai-service/src/main/java/com/ford/service/outbox/OutboxConfiguration.java: deterministic outbox bean graph.
- ford-ai-service/src/main/java/com/ford/service/outbox/OutboxDeliveryJob.java: plain scheduled job bean.
- ford-ai-service/src/main/java/com/ford/service/outbox/OutboxRecoveryJob.java: plain recovery job bean.
- ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordIngestionConfiguration.java: ingestion composition and job registration.
- ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncJob.java: plain scheduled job bean.
- ford-ai-service/src/main/java/com/ford/service/observability/CapabilityDiagnostics.java: redacted startup capability summary.
- ford-ai-service/src/main/java/com/ford/service/observability/ObservabilityConfiguration.java: diagnostics composition.
- ford-ai-service/src/main/resources/application.yml: common property mappings.
- ford-ai-service/src/main/resources/application-dev.yml: development profile defaults.
- ford-ai-service/src/main/resources/application-prod.yml: production profile defaults without secrets.
- ford-ai-service/.env.example and .env.prod.example: safe environment templates.
- ford-ai-service/README.md and ford-ai-service/docs/operations.md: operating guidance.

Tests are added beside the existing package tests under ford-ai-service/src/test/java/com/ford/service/.

---

### Task 1: Introduce typed properties and profile overlays

**Files:**
- Create: ford-ai-service/src/main/java/com/ford/service/ai/configuration/AiProperties.java
- Create: ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationProperties.java
- Create: ford-ai-service/src/main/java/com/ford/service/outbox/OutboxProperties.java
- Create: ford-ai-service/src/main/resources/application-dev.yml
- Create: ford-ai-service/src/main/resources/application-prod.yml
- Modify: ford-ai-service/src/main/resources/application.yml
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncProperties.java
- Test: ford-ai-service/src/test/java/com/ford/service/config/ConfigurationPropertiesSliceTest.java

**Interfaces:**
- AiProperties binds ford.ai and contains cloud/local provider IDs, models, embedding settings, and enable flags.
- ConversationProperties binds ford.conversations and exposes humanHandoffEnabled().
- OutboxProperties binds ford.outbox and exposes enabled(), fixedDelayMs(), recoveryDelayMs(), maxAttempts(), leaseSeconds(), backoffBaseSeconds(), and backoffMaxSeconds().
- Existing FORD_* variables remain the external configuration interface.

- [ ] **Step 1: Write the failing property binding test.**

Use a Spring slice test with these overrides:

    ford.ai.cloud-enabled=true
    ford.ai.local-enabled=false
    ford.ai.cloud-chat-provider=openai
    ford.conversations.human-handoff.enabled=false
    ford.outbox.enabled=false

Assert the bound cloud/local values, humanHandoffEnabled() == false, and
OutboxProperties.enabled() == false.

- [ ] **Step 2: Run the focused test and verify it fails.**

    ./gradlew test --tests com.ford.service.config.ConfigurationPropertiesSliceTest

Expected: compilation or context failure because the new properties do not yet
exist.

- [ ] **Step 3: Implement and register the typed properties.**

Use JavaBean-style properties compatible with the current Spring Boot binding:

    @ConfigurationProperties(prefix = "ford.conversations")
    public class ConversationProperties {
        private boolean humanHandoffEnabled = true;

        public boolean humanHandoffEnabled() {
            return humanHandoffEnabled;
        }

        public void setHumanHandoffEnabled(boolean value) {
            humanHandoffEnabled = value;
        }
    }

AiProperties must replace the duplicate nested provider property class.
OutboxProperties must contain all existing timing and retry values. Register
each class from its owning configuration with EnableConfigurationProperties.

- [ ] **Step 4: Add profile overlays and common mappings.**

Add application-dev.yml and application-prod.yml with profile activation and
the same explicit handoff mapping:

    spring:
      config:
        activate:
          on-profile: dev
    ford:
      conversations:
        human-handoff:
          enabled: \${FORD_HUMAN_HANDOFF_ENABLED:true}

Use prod instead of dev in application-prod.yml. Keep secrets external.
Move all existing FORD_* environment mappings into the typed namespaces once in
application.yml and remove duplicate provider values.

- [ ] **Step 5: Run the focused test and commit.**

    ./gradlew test --tests com.ford.service.config.ConfigurationPropertiesSliceTest

Expected: BUILD SUCCESSFUL.

    git add ford-ai-service/src/main/java/com/ford/service/ai/configuration/AiProperties.java ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationProperties.java ford-ai-service/src/main/java/com/ford/service/outbox/OutboxProperties.java ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncProperties.java ford-ai-service/src/main/resources/application.yml ford-ai-service/src/main/resources/application-dev.yml ford-ai-service/src/main/resources/application-prod.yml ford-ai-service/src/test/java/com/ford/service/config/ConfigurationPropertiesSliceTest.java
    git commit -m "refactor: add typed capability properties"

### Task 2: Make AI provider wiring deterministic

**Files:**
- Create: ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/UnavailableChatCompletionPort.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/ProviderRoutingChatCompletionPort.java
- Test: ford-ai-service/src/test/java/com/ford/service/ai/UnavailableChatCompletionPortTest.java
- Test: ford-ai-service/src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java

**Interfaces:**
- UnavailableChatCompletionPort implements ChatCompletionPort and throws
  IllegalStateException("No AI provider is configured") from complete().
- SpringAiProviderConfiguration produces ModelProviderPolicy from AiProperties
  and available OpenAiChatModel/OllamaChatModel beans.
- SpringAiConfiguration produces exactly one ChatCompletionPort,
  AssistantOrchestrator, and AssistantService without ConditionalOnBean.

- [ ] **Step 1: Write failing empty-provider and routing tests.**

Assert that the unavailable port throws the stable message. Add a slice test
with no usable model bean that still finds ChatCompletionPort,
AssistantOrchestrator, and AssistantService. Add routing coverage with two fake
ProviderChatCompletionPort implementations and verify the selected descriptor
has a matching port.

- [ ] **Step 2: Run the focused tests and verify failure.**

    ./gradlew test --tests com.ford.service.ai.UnavailableChatCompletionPortTest --tests com.ford.service.ai.SpringAiConfigurationSliceTest

Expected: missing class or missing beans before implementation.

- [ ] **Step 3: Add the unavailable port.**

    public final class UnavailableChatCompletionPort implements ChatCompletionPort {
        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            throw new IllegalStateException("No AI provider is configured");
        }
    }

- [ ] **Step 4: Wire concrete Spring AI models separately.**

Use Spring AI 2.0.1's ChatClient.builder(ChatModel).build() API. Create a
provider port only when its matching model exists and its enable flag is true.
Use ObjectProvider<OpenAiChatModel> and ObjectProvider<OllamaChatModel> so an
unconfigured model does not break startup.

Set each ProviderDescriptor.healthy value from both the enable flag and model
presence. The final List<ProviderChatCompletionPort> must contain only
enabled, available providers. Do not register a null @Bean.

- [ ] **Step 5: Remove order-sensitive AI conditions.**

Change SpringAiConfiguration to build the provider list from optional concrete
model ports, return UnavailableChatCompletionPort when the list is empty, and
always create the orchestrator and assistant service. Remove ConditionalOnBean
from AI bean methods. Retain ObjectProvider for optional catalog, finance, and
knowledge tools.

- [ ] **Step 6: Run focused AI tests and commit.**

    ./gradlew test --tests 'com.ford.service.ai.*'

Expected: BUILD SUCCESSFUL with no integration test task.

    git add ford-ai-service/src/main/java/com/ford/service/ai ford-ai-service/src/test/java/com/ford/service/ai
    git commit -m "refactor: make ai provider wiring deterministic"

### Task 3: Add the handoff policy and enforce bot-only mode

**Files:**
- Create: ford-ai-service/src/main/java/com/ford/service/conversations/application/HumanHandoffPolicy.java
- Modify: ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationProperties.java
- Modify: ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java
- Modify: ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java
- Test: ford-ai-service/src/test/java/com/ford/service/conversations/HumanHandoffPolicyTest.java
- Modify: ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java
- Modify: ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java
- Modify: ford-ai-service/src/test/java/com/ford/service/conversations/ConversationConfigurationSliceTest.java

**Interfaces:**
- HumanHandoffPolicy(boolean enabled) exposes enabled(), fallbackText(), and
  shouldCreateHandoff(boolean requested).
- ConversationWorkflow receives HumanHandoffPolicy directly.
- SpringAiAssistantService receives HumanHandoffPolicy and uses it for
  provider-failure fallback metadata.

- [ ] **Step 1: Write failing policy and workflow tests.**

Assert:

    new HumanHandoffPolicy(false).shouldCreateHandoff(true) == false
    new HumanHandoffPolicy(true).shouldCreateHandoff(true) == true

Extend ConversationWorkflowTest with a disabled case: the repository records a
ConversationTurn using the bot fallback, handoffDecision remains null, and the
publisher contains only OutboundDeliveryRequested.

Extend SpringAiAssistantServiceTest so provider failure with a disabled policy
returns text "No pude generar una respuesta en este momento. Intenta de nuevo en unos segundos.",
requestsHuman == false, provider == "fallback", and modelVersion == "none".

- [ ] **Step 2: Run focused tests and verify failure.**

    ./gradlew test --tests com.ford.service.conversations.HumanHandoffPolicyTest --tests com.ford.service.conversations.ConversationWorkflowTest --tests com.ford.service.ai.SpringAiAssistantServiceTest

Expected: the new disabled-handoff assertions fail.

- [ ] **Step 3: Implement the policy.**

Use these constants and methods:

    public static final String BOT_FALLBACK =
        "No pude generar una respuesta en este momento. Intenta de nuevo en unos segundos.";
    private static final String HUMAN_FALLBACK =
        "En este momento te comunicaré con un asesor humano.";

    public boolean enabled() { return enabled; }
    public String fallbackText() { return enabled ? HUMAN_FALLBACK : BOT_FALLBACK; }
    public boolean shouldCreateHandoff(boolean requested) { return enabled && requested; }

- [ ] **Step 4: Apply the policy in the AI fallback.**

Replace the unconditional human fallback in SpringAiAssistantService with
policy.fallbackText() and policy.enabled(). Add a warning with provider,
model, and exception type only. Sanitize or omit the exception message.

- [ ] **Step 5: Apply the policy in ConversationWorkflow.**

Before requestHumanHandoff, check handoffPolicy.shouldCreateHandoff(true).
When it is false, persist a ConversationTurn using handoffPolicy.fallbackText()
and publish OutboundDeliveryRequested. Do not call requestHumanHandoff or
publish HandoffRequested.

- [ ] **Step 6: Run focused tests and commit.**

    ./gradlew test --tests 'com.ford.service.conversations.*' --tests com.ford.service.ai.SpringAiAssistantServiceTest

Expected: BUILD SUCCESSFUL and both handoff branches covered.

    git add ford-ai-service/src/main/java/com/ford/service/conversations ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java ford-ai-service/src/test/java/com/ford/service/conversations ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java
    git commit -m "feat: make human handoff configurable"

### Task 4: Remove remaining order-sensitive module wiring

**Files:**
- Modify: ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaChannelConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/outbox/OutboxConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/outbox/OutboxDeliveryJob.java
- Modify: ford-ai-service/src/main/java/com/ford/service/outbox/OutboxRecoveryJob.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordIngestionConfiguration.java
- Modify: ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncJob.java
- Test: ford-ai-service/src/test/java/com/ford/service/channels/MetaChannelConfigurationSliceTest.java
- Test: ford-ai-service/src/test/java/com/ford/service/outbox/OutboxConfigurationSliceTest.java
- Test: ford-ai-service/src/test/java/com/ford/service/ingestion/IngestionConfigurationSliceTest.java

**Interfaces:**
- Outbox jobs become constructor-only classes with no component or conditional
  annotations; OutboxConfiguration creates them only for a valid outbox graph.
- FordSyncJob becomes a constructor-only class; ingestion configuration owns
  its bean registration.

- [ ] **Step 1: Write disabled-capability slice tests.**

Assert that ford.outbox.enabled=false has no OutboxDeliveryJob or
OutboxRecoveryJob, ford.ingestion.enabled=false has no FordSyncJob, and Meta
configuration obtains Boot's ObjectMapper without defining a second module-local
mapper.

- [ ] **Step 2: Run focused tests and verify failure.**

    ./gradlew test --tests com.ford.service.channels.MetaChannelConfigurationSliceTest --tests com.ford.service.outbox.OutboxConfigurationSliceTest --tests com.ford.service.ingestion.IngestionConfigurationSliceTest

Expected: the new assertions fail until registration is moved into the
configuration roots.

- [ ] **Step 3: Remove the Meta-local mapper.**

Delete the ObjectMapper bean from MetaChannelConfiguration. Keep channel
adapter beans dependent on Spring Boot's ObjectMapper and remove unused
MetaWebhookProperties parameters.

- [ ] **Step 4: Move scheduled job creation into configuration.**

Remove Component, ConditionalOnBean, and ConditionalOnProperty from the job
classes. Register them inside their property-guarded configurations. Use
OutboxProperties instead of repeated Value expressions.

When ford.outbox.enabled=true, require an OutboxDeliveryRouter directly. This
causes an invalid outbox/delivery combination to fail clearly rather than
creating a half-functional graph.

- [ ] **Step 5: Keep ingestion optional but complete.**

Keep FordIngestionConfiguration guarded by ford.ingestion.enabled=true,
register all source adapters and FordSyncJob inside it, and preserve empty
snapshot adapters for missing source endpoints.

- [ ] **Step 6: Run focused tests and commit.**

    ./gradlew test --tests 'com.ford.service.channels.*' --tests 'com.ford.service.outbox.*' --tests 'com.ford.service.ingestion.*'

Expected: BUILD SUCCESSFUL.

    git add ford-ai-service/src/main/java/com/ford/service/channels ford-ai-service/src/main/java/com/ford/service/outbox ford-ai-service/src/main/java/com/ford/service/ingestion ford-ai-service/src/test/java/com/ford/service/channels ford-ai-service/src/test/java/com/ford/service/outbox ford-ai-service/src/test/java/com/ford/service/ingestion
    git commit -m "refactor: make optional module wiring explicit"

### Task 5: Add redacted capability diagnostics

**Files:**
- Create: ford-ai-service/src/main/java/com/ford/service/observability/CapabilityDiagnostics.java
- Create: ford-ai-service/src/main/java/com/ford/service/observability/ObservabilityConfiguration.java
- Test: ford-ai-service/src/test/java/com/ford/service/observability/CapabilityDiagnosticsTest.java

**Interfaces:**
- CapabilityDiagnostics exposes summary() and logs it once on ApplicationReadyEvent.
- The summary reports only boolean enabled/configured states and allowlisted
  provider IDs; secret values and full URLs are never returned.

- [ ] **Step 1: Write the pure diagnostics test.**

Construct diagnostics with typed properties and assert the summary contains
humanHandoff=disabled, ai.cloud=enabled, ai.local=disabled,
meta.outbound=disabled, outbox=disabled, and observability.traces=disabled.
Assert that access-token-looking values, passwords, query parameters, and app
secrets are absent.

- [ ] **Step 2: Run the focused test and verify failure.**

    ./gradlew test --tests com.ford.service.observability.CapabilityDiagnosticsTest

Expected: missing class or failing assertions.

- [ ] **Step 3: Implement diagnostics and startup logging.**

Create the summary from typed properties and ObjectProvider presence. Use an
ApplicationReadyEvent listener to emit one INFO log. Use fixed field names and
bounded provider IDs; do not include arbitrary property values.

- [ ] **Step 4: Run focused observability tests and commit.**

    ./gradlew test --tests 'com.ford.service.observability.*'

Expected: BUILD SUCCESSFUL.

    git add ford-ai-service/src/main/java/com/ford/service/observability ford-ai-service/src/test/java/com/ford/service/observability/CapabilityDiagnosticsTest.java
    git commit -m "feat: add redacted capability diagnostics"

### Task 6: Update environment examples and operating documentation

**Files:**
- Modify: ford-ai-service/.env.example
- Modify: ford-ai-service/.env.prod.example
- Modify: ford-ai-service/README.md
- Modify: ford-ai-service/docs/operations.md

- [ ] **Step 1: Add explicit handoff examples.**

Add to .env.example:

    # Set false while testing bot-only behavior; production defaults to true.
    FORD_HUMAN_HANDOFF_ENABLED=false

Add to .env.prod.example:

    # Choose explicitly for the deployment; do not inherit local bot-only mode.
    FORD_HUMAN_HANDOFF_ENABLED=true

- [ ] **Step 2: Document profile and capability behavior.**

Document SPRING_PROFILES_ACTIVE=dev|prod, the provider matrix, bot-only
behavior, and these database/event effects:

    FORD_HUMAN_HANDOFF_ENABLED=false
    -> no handoff_ticket for assistant escalation
    -> no HandoffRequested event
    -> fallback is persisted and sent through the normal outbound path

Document mise run test:unit as the supported non-integration command and state
that mise run test:integration is intentionally not part of this verification.

- [ ] **Step 3: Run documentation and secret checks.**

    git diff --check
    rg -n 'sk-|EAA|replace-with-secret|FORD_HUMAN_HANDOFF_ENABLED|SPRING_PROFILES_ACTIVE' ford-ai-service/.env.example ford-ai-service/.env.prod.example ford-ai-service/README.md ford-ai-service/docs/operations.md

Expected: only example secret text and documented property names appear; no
live credentials are present.

- [ ] **Step 4: Commit documentation.**

    git add ford-ai-service/.env.example ford-ai-service/.env.prod.example ford-ai-service/README.md ford-ai-service/docs/operations.md
    git commit -m "docs: document capability profiles and bot-only mode"

### Task 7: Full unit and slice verification

**Files:**
- Modify only failing tests or implementation files discovered by verification.
- Test: all existing tests under ford-ai-service/src/test/java that belong to
  the normal test task.

- [ ] **Step 1: Run the normal unit task only.**

    mise run test:unit

Expected: BUILD SUCCESSFUL; do not run mise run test:check because that task
includes the integration test source set.

- [ ] **Step 2: Verify no integration test executed.**

Confirm the output contains the normal Gradle test task but not :integrationTest.

- [ ] **Step 3: Run configuration-focused tests directly.**

    ./gradlew test --tests '*ConfigurationSliceTest' --tests '*DiagnosticsTest'

Expected: all selected slice and diagnostics tests pass.

- [ ] **Step 4: Inspect the final diff and state.**

    git diff --check
    git status --short
    git log --oneline -8

Expected: no whitespace errors, no untracked secret files, and only approved
configuration, tests, profiles, and documentation are changed.

- [ ] **Step 5: Commit only if verification required a tracked fix.**

    git add ford-ai-service
    git commit -m "test: verify deterministic configuration paths"

Do not create this commit if Task 7 required no tracked changes.

## Completion criteria

- The application starts with no AI provider configured and exposes a controlled assistant fallback.
- Cloud and local provider descriptors correspond exactly to available provider ports.
- FORD_HUMAN_HANDOFF_ENABLED=false prevents handoff persistence and events, including provider-failure fallback paths.
- Meta uses Boot's managed ObjectMapper.
- Disabled outbox and ingestion capabilities do not register scheduled jobs.
- Startup diagnostics are redacted and report effective capability state.
- mise run test:unit passes without executing integration tests.
- No database migration or secret file is added.
