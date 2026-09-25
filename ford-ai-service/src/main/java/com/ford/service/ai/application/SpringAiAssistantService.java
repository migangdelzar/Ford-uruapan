package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.shared.HumanHandoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.configuration.ClassificationProperties;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SpringAiAssistantService implements AssistantService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistantService.class);

    private final AssistantOrchestrator orchestrator;
    private final HumanHandoffPolicy handoffPolicy;
    private final ModelProviderPolicy providerPolicy;
    private final boolean logProviderErrors;
    private final ConversationDecisionService decisionService;
    private final DecisionPolicy decisionPolicy;

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, new HumanHandoffPolicy(true), providerPolicy, false,
                defaultDecisionService());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, false, defaultDecisionService());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, defaultDecisionService());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, new DecisionPolicy());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.providerPolicy = Objects.requireNonNull(providerPolicy);
        this.logProviderErrors = logProviderErrors;
        this.decisionService = Objects.requireNonNull(decisionService);
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy);
    }

    @Override
    public AssistantTurn respond(AiExecutionContext context, String userText) {
        Objects.requireNonNull(context);
        if (context.tenantId() == null || userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("tenant and user text are required");
        }

        var conversationId = context.conversationId() == null ? UUID.randomUUID() : context.conversationId();
        var decision = decisionService.decide(new ClassificationContext(
                context.tenantId(), conversationId, userText, context.memoryContext(),
                "BOT_ACTIVE", context.containsSensitiveData()));
        try {
            AssistantResponse response = orchestrator.respond(new AssistantRequest(
                    context.tenantId(), conversationId, userText, "BOT_ACTIVE",
                    context.memoryContext(),
                    context.containsSensitiveData(), decision));
            var requestsHuman = response.handoffRequired()
                    || decisionPolicy.mayCreateHandoff(decision, handoffPolicy.enabled());
            return new AssistantTurn(response.content(), decision.toLegacyIntent(), requestsHuman,
                    response.sourceDocumentIds(), response.provider(), modelVersion(response.provider()), decision);
        } catch (RuntimeException exception) {
            log.warn("AI completion failed: providers={} exceptionType={} {}",
                    providerSummary(), exception.getClass().getSimpleName(),
                    AiProviderFailureDiagnostics.describe(exception, logProviderErrors));
            return new AssistantTurn(handoffPolicy.fallbackText(), decision.toLegacyIntent(),
                    false, List.of(), "fallback", "none", decision);
        }
    }

    private static ConversationDecisionService defaultDecisionService() {
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        return new ConversationDecisionService(rules, rules, new ClassificationProperties());
    }

    private String providerSummary() {
        return providerPolicy.providers().stream()
                .map(provider -> provider.id() + ":" + provider.modelVersion())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private String modelVersion(String provider) {
        return providerPolicy.providers().stream()
                .filter(candidate -> candidate.id().equals(provider))
                .map(ProviderDescriptor::modelVersion)
                .findFirst()
                .orElse("unknown");
    }
}
