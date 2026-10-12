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
    private final AssistantScopePolicy scopePolicy;
    private final LeadQualificationPolicy leadQualificationPolicy;
    private final InputSecurityPolicy inputSecurityPolicy;

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, new HumanHandoffPolicy(true), providerPolicy, false,
                defaultDecisionService(), new DecisionPolicy(), new AssistantScopePolicy(new IntentRouter()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, false, defaultDecisionService(),
                new DecisionPolicy(), new AssistantScopePolicy(new IntentRouter()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, defaultDecisionService(),
                new DecisionPolicy(), new AssistantScopePolicy(new IntentRouter()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, new DecisionPolicy(),
                new AssistantScopePolicy(new IntentRouter()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                new AssistantScopePolicy(new IntentRouter()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                scopePolicy, new LeadQualificationPolicy());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy,
                                    LeadQualificationPolicy leadQualificationPolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                scopePolicy, leadQualificationPolicy, new InputSecurityPolicy(new ClassificationProperties()));
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy,
                                    LeadQualificationPolicy leadQualificationPolicy,
                                    InputSecurityPolicy inputSecurityPolicy) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.providerPolicy = Objects.requireNonNull(providerPolicy);
        this.logProviderErrors = logProviderErrors;
        this.decisionService = Objects.requireNonNull(decisionService);
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy);
        this.scopePolicy = Objects.requireNonNull(scopePolicy);
        this.leadQualificationPolicy = Objects.requireNonNull(leadQualificationPolicy);
        this.inputSecurityPolicy = Objects.requireNonNull(inputSecurityPolicy);
    }

    @Override
    public AssistantTurn respond(AiExecutionContext context, String userText) {
        Objects.requireNonNull(context);
        if (context.tenantId() == null || userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("tenant and user text are required");
        }

        var conversationId = context.conversationId() == null ? UUID.randomUUID() : context.conversationId();
        var securedInput = inputSecurityPolicy.apply(userText, context.memoryContext(),
                context.leadJourneyContext(), context.containsSensitiveData());
        var started = System.nanoTime();
        log.info("AI turn started: conversationId={} memoryChars={} sensitiveData={} inputGuardrail={} piiRedacted={} untrustedContextOmitted={}",
                conversationId, context.memoryContext() == null ? 0 : context.memoryContext().length(),
                securedInput.containsSensitiveData(), securedInput.reason(), securedInput.piiRedacted(),
                securedInput.untrustedContextOmitted());
        if (securedInput.blocked()) {
            log.warn("AI turn blocked by input security policy: conversationId={} reason={}",
                    conversationId, securedInput.reason());
            return new AssistantTurn(inputSecurityResponse(securedInput.reason()), Intent.GENERAL, false,
                    List.of(), "input-security-guard", "none", ConversationDecision.deterministicDefault());
        }
        var decision = decisionService.decide(new ClassificationContext(
                context.tenantId(), conversationId, securedInput.message(), securedInput.memoryContext(),
                "BOT_ACTIVE", securedInput.containsSensitiveData()));
        if (!decisionService.hasConfidentLeadEvidence(decision)) {
            var leadAssessment = leadQualificationPolicy.assess(
                    securedInput.message(), securedInput.memoryContext(), decision);
            decision = leadQualificationPolicy.enrich(decision, leadAssessment);
        }
        log.info("AI turn classified: conversationId={} intent={} leadTemperature={} leadStage={} toolFamily={} handoff={} nextAction={} evidenceCount={}",
                conversationId, decision.intent(), decision.leadTemperature(), decision.leadStage(),
                decision.toolFamily(), decision.handoffRecommendation(), decision.nextAction(), decision.evidence().size());
        if (!scopePolicy.allows(securedInput.message(), decision, securedInput.memoryContext())) {
            log.info("AI turn completed by scope guard: conversationId={} durationMs={}",
                    conversationId, elapsedMs(started));
            return new AssistantTurn(scopePolicy.scopeResponse(), decision.toLegacyIntent(), false,
                    List.of(), "scope-guard", "none", decision);
        }
        try {
            AssistantResponse response = orchestrator.respond(new AssistantRequest(
                    context.tenantId(), conversationId, securedInput.message(), "BOT_ACTIVE",
                    securedInput.memoryContext(),
                    securedInput.containsSensitiveData(), decision, securedInput.leadJourneyContext()));
            var requestsHuman = response.handoffRequired()
                    || decisionPolicy.mayCreateHandoff(decision, handoffPolicy.enabled(context.tenantId()));
            log.info("AI turn completed: conversationId={} provider={} model={} requestsHuman={} sourceCount={} journeyProposal={} durationMs={}",
                    conversationId, response.provider(), modelVersion(response.provider()), requestsHuman,
                    response.sourceDocumentIds().size(), response.journeyProposal() != null, elapsedMs(started));
            return new AssistantTurn(response.content(), decision.toLegacyIntent(), requestsHuman,
                    response.sourceDocumentIds(), response.provider(), modelVersion(response.provider()), decision,
                    response.journeyProposal());
        } catch (RuntimeException exception) {
            log.error("AI turn failed: conversationId={} providers={} exceptionType={} durationMs={} {}",
                    conversationId,
                    providerSummary(), exception.getClass().getSimpleName(),
                    elapsedMs(started),
                    AiProviderFailureDiagnostics.describe(exception, logProviderErrors));
            return new AssistantTurn(handoffPolicy.fallbackText(), decision.toLegacyIntent(),
                    false, List.of(), "fallback", "none", decision);
        }
    }

    private static String inputSecurityResponse(InputSecurityPolicy.Reason reason) {
        return switch (reason) {
            case PROMPT_INJECTION -> "Puedo ayudarte con información de vehículos, ofertas y financiamiento Ford. ¿Qué te gustaría consultar?";
            case SENSITIVE_DATA_BLOCKED -> "Por seguridad, elimina los datos personales o financieros del mensaje y vuelve a enviarlo. Puedo ayudarte con tu consulta Ford.";
            case NONE -> "Puedo ayudarte con información de Ford. ¿Qué te gustaría consultar?";
        };
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
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
