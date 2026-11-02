package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.shared.HumanHandoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.configuration.ClassificationProperties;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SpringAiAssistantService implements AssistantService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistantService.class);
    private static final Pattern PRIOR_ASSISTANT_TURN = Pattern.compile(
            "(?im)^\\s*[-*]?\\s*(usuario|asistente|user|assistant)\\s*:\\s*\\S");
    private static final Pattern SESSION_SUMMARY = Pattern.compile("(?im)^\\s*resumen de sesión:\\s*(.+)$");

    private final AssistantOrchestrator orchestrator;
    private final HumanHandoffPolicy handoffPolicy;
    private final ModelProviderPolicy providerPolicy;
    private final boolean logProviderErrors;
    private final ConversationDecisionService decisionService;
    private final DecisionPolicy decisionPolicy;
    private final AssistantScopePolicy scopePolicy;
    private final LeadQualificationPolicy leadQualificationPolicy;
    private final InputSecurityPolicy inputSecurityPolicy;
    private final AssistantPersona assistantPersona;
    private final DeterministicCatalogAnswerService deterministicCatalogAnswer;
    private final boolean responseRepeatCheckEnabled;
    private final com.ford.service.shared.AdvisorResponseRepeatPolicy repeatPolicy =
            new com.ford.service.shared.AdvisorResponseRepeatPolicy();

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
                                    boolean responseRepeatCheckEnabled) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, defaultDecisionService(),
                new DecisionPolicy(), new AssistantScopePolicy(new IntentRouter()), new LeadQualificationPolicy(),
                new InputSecurityPolicy(new ClassificationProperties()), new AssistantPersona(), null,
                responseRepeatCheckEnabled);
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
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                scopePolicy, leadQualificationPolicy, inputSecurityPolicy, new AssistantPersona());
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy,
                                    LeadQualificationPolicy leadQualificationPolicy,
                                    InputSecurityPolicy inputSecurityPolicy,
                                    AssistantPersona assistantPersona) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                scopePolicy, leadQualificationPolicy, inputSecurityPolicy, assistantPersona, null);
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy,
                                    LeadQualificationPolicy leadQualificationPolicy,
                                     InputSecurityPolicy inputSecurityPolicy,
                                     AssistantPersona assistantPersona,
                                     DeterministicCatalogAnswerService deterministicCatalogAnswer) {
        this(orchestrator, handoffPolicy, providerPolicy, logProviderErrors, decisionService, decisionPolicy,
                scopePolicy, leadQualificationPolicy, inputSecurityPolicy, assistantPersona,
                deterministicCatalogAnswer, true);
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors,
                                    ConversationDecisionService decisionService,
                                    DecisionPolicy decisionPolicy,
                                    AssistantScopePolicy scopePolicy,
                                    LeadQualificationPolicy leadQualificationPolicy,
                                    InputSecurityPolicy inputSecurityPolicy,
                                    AssistantPersona assistantPersona,
                                    DeterministicCatalogAnswerService deterministicCatalogAnswer,
                                    boolean responseRepeatCheckEnabled) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.providerPolicy = Objects.requireNonNull(providerPolicy);
        this.logProviderErrors = logProviderErrors;
        this.decisionService = Objects.requireNonNull(decisionService);
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy);
        this.scopePolicy = Objects.requireNonNull(scopePolicy);
        this.leadQualificationPolicy = Objects.requireNonNull(leadQualificationPolicy);
        this.inputSecurityPolicy = Objects.requireNonNull(inputSecurityPolicy);
        this.assistantPersona = Objects.requireNonNull(assistantPersona);
        this.deterministicCatalogAnswer = deterministicCatalogAnswer;
        this.responseRepeatCheckEnabled = responseRepeatCheckEnabled;
    }

    @Override
    public AssistantTurn respond(AiExecutionContext context, String userText) {
        Objects.requireNonNull(context);
        if (context.tenantId() == null || userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("tenant and user text are required");
        }

        var conversationId = context.conversationId() == null ? UUID.randomUUID() : context.conversationId();
        var trace = new DecisionTraceCollector();
        var securedInput = inputSecurityPolicy.apply(userText, context.memoryContext(),
                context.leadJourneyContext(), context.containsSensitiveData());
        trace.record(new DecisionTraceEvent("security", "input_security_evaluated", null, "none", null,
                securedInput.blocked() ? "blocked" : "allowed", null, java.time.Instant.now()));
        var started = System.nanoTime();
        log.info("AI turn started: conversationId={} sensitiveData={} inputGuardrail={} piiRedacted={} untrustedContextOmitted={}",
                conversationId,
                securedInput.containsSensitiveData(), securedInput.reason(), securedInput.piiRedacted(),
                securedInput.untrustedContextOmitted());
        if (securedInput.blocked()) {
            log.warn("AI turn blocked by input security policy: conversationId={} reason={}",
                    conversationId, securedInput.reason());
            return new AssistantTurn(inputSecurityResponse(securedInput.reason()), Intent.GENERAL, false,
                    List.of(), "input-security-guard", "none", ConversationDecision.deterministicDefault(),
                    null, trace.snapshot(), com.ford.service.ai.api.ResponseKind.SAFE_FALLBACK);
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
        trace.record(new DecisionTraceEvent("classifier", "classifier_completed", decision.intent().name(),
                "classifier", null, "completed", decision.evidence().size(), java.time.Instant.now()));
        trace.record(new DecisionTraceEvent("entity_resolution", "vehicle_entities_resolved", null, "none", null,
                decision.vehicleEntities().status().name().toLowerCase(java.util.Locale.ROOT), null,
                java.time.Instant.now()));
        var scopeDecision = scopePolicy.evaluate(securedInput.message(), decision, securedInput.memoryContext());
        trace.record(new DecisionTraceEvent("scope", scopeDecision.reasonCode(), null, "none", null,
                scopeDecision.status() == ScopeDecision.Status.ALLOWED ? "allowed" : "denied", null,
                java.time.Instant.now()));
        if (scopeDecision.status() == ScopeDecision.Status.DENIED) {
            log.info("AI turn completed by scope guard: conversationId={} reason={} classifiedIntent={} toolFamily={} durationMs={}",
                    conversationId, scopeDecision.reasonCode(), decision.intent(), decision.toolFamily(), elapsedMs(started));
            return new AssistantTurn(introduceOnce(scopePolicy.scopeResponse(), conversationId,
                    securedInput.memoryContext()), decision.toLegacyIntent(), false,
                    List.of(), "scope-guard", "none", decision, null, trace.snapshot(),
                    com.ford.service.ai.api.ResponseKind.SAFE_FALLBACK);
        }
        try {
            trace.record(new DecisionTraceEvent("route", "decision_route_selected", decision.intent().name(),
                    "none", null, "completed", decision.evidence().size(), java.time.Instant.now()));
            var request = new AssistantRequest(
                    context.tenantId(), conversationId, securedInput.message(), "BOT_ACTIVE",
                    securedInput.memoryContext(),
                    securedInput.containsSensitiveData(), decision, securedInput.leadJourneyContext(),
                    context.recentAssistantReplies(), context.repairInstruction(), trace);
            AssistantResponse response = deterministicCatalogAnswer == null
                    ? orchestrator.respond(request)
                    : deterministicCatalogAnswer.tryAnswer(request).orElseGet(() -> orchestrator.respond(request));
            var repeat = responseRepeatCheckEnabled
                    ? repeatPolicy.evaluate(securedInput.message(), context.recentAssistantReplies(), response.content())
                    : new com.ford.service.shared.AdvisorResponseRepeatPolicy.Verdict(false, "disabled");
            if (repeat.repeated()) {
                log.info("AI response repeat detected: conversationId={} reason={}", conversationId, repeat.reasonCode());
                response = orchestrator.respond(request.withRepairInstruction(
                        "Responda la pregunta actual con contenido nuevo y avance un paso. No repita ninguna respuesta anterior. " +
                                "Respuestas previas: " + String.join(" | ", context.recentAssistantReplies())));
                repeat = repeatPolicy.evaluate(securedInput.message(), context.recentAssistantReplies(), response.content());
                if (repeat.repeated()) {
                    log.warn("AI response repeat repair rejected: conversationId={} reason={}", conversationId, repeat.reasonCode());
                    throw new AssistantTurnUnavailableException(AssistantTurnUnavailableException.Disposition.REVIEW);
                }
            }
            var requestsHuman = response.handoffRequired()
                    || decisionPolicy.mayCreateHandoff(decision, handoffPolicy.enabled(context.tenantId()));
            trace.record(new DecisionTraceEvent("answer", "answer_generation_completed", decision.intent().name(),
                    "answer", null, "completed", response.sourceDocumentIds().size(), java.time.Instant.now()));
            trace.record(new DecisionTraceEvent("evidence", "response_evidence_assessed", null, "none", null,
                    switch (response.evidenceStatus()) {
                        case READ_ONLY_TOOL_RETURNED, KNOWLEDGE_DOCUMENTS_RETURNED -> "supported";
                        case READ_ONLY_TOOL_RETURNED_EMPTY, READ_ONLY_TOOL_FAILED,
                                REQUIRED_TOOL_NOT_CALLED -> "unknown";
                        case NOT_REQUIRED -> "not_applicable";
                    }, response.sourceDocumentIds().size(), java.time.Instant.now()));
            trace.record(new DecisionTraceEvent("quality", "quality_checks_passed", decision.intent().name(),
                    "none", null, response.responseKind() == com.ford.service.ai.api.ResponseKind.CLARIFICATION
                    ? "clarification" : "pass", null, java.time.Instant.now()));
            trace.record(new DecisionTraceEvent("finalization", "assistant_turn_finalized", null, "none", null,
                    "finalized", response.sourceDocumentIds().size(), java.time.Instant.now()));
            log.info("AI turn completed: conversationId={} provider={} model={} requestsHuman={} sourceCount={} journeyProposal={} durationMs={}",
                    conversationId, response.provider(), modelVersion(response.provider()), requestsHuman,
                    response.sourceDocumentIds().size(), response.journeyProposal() != null, elapsedMs(started));
            return new AssistantTurn(introduceOnce(response.content(), conversationId,
                    securedInput.memoryContext()), decision.toLegacyIntent(), requestsHuman,
                    response.sourceDocumentIds(), response.provider(), modelVersion(response.provider()), decision,
                    response.journeyProposal(), trace.snapshot(), response.responseKind());
        } catch (RuntimeException exception) {
            log.error("AI turn failed: conversationId={} providers={} exceptionType={} durationMs={} {}",
                    conversationId,
                    providerSummary(), exception.getClass().getSimpleName(),
                    elapsedMs(started),
                    AiProviderFailureDiagnostics.describe(exception, logProviderErrors));
            if (exception instanceof AssistantTurnUnavailableException unavailable) {
                throw unavailable;
            }
            var disposition = AiProviderFailureDiagnostics.classify(exception).retryable()
                    ? AssistantTurnUnavailableException.Disposition.RETRY
                    : AssistantTurnUnavailableException.Disposition.REVIEW;
            throw new AssistantTurnUnavailableException(disposition);
        }
    }

    private static String inputSecurityResponse(InputSecurityPolicy.Reason reason) {
        return switch (reason) {
            case PROMPT_INJECTION -> "Puedo ayudarle con información de vehículos, ofertas y financiamiento Ford. ¿Qué desea consultar?";
            case SENSITIVE_DATA_BLOCKED -> "Por seguridad, elimine los datos personales o financieros del mensaje y vuelva a enviarlo. Puedo ayudarle con su consulta Ford.";
            case NONE -> "Puedo ayudarle con información de Ford. ¿Qué desea consultar?";
        };
    }

    private String introduceOnce(String response, UUID conversationId, String memoryContext) {
        if (hasPriorConversation(memoryContext)) return response;
        return assistantPersona.introductionFor(conversationId) + "\n" + response;
    }

    private static boolean hasPriorConversation(String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()) return false;
        if (PRIOR_ASSISTANT_TURN.matcher(memoryContext).find()) return true;
        var summary = SESSION_SUMMARY.matcher(memoryContext);
        return summary.find() && !summary.group(1).isBlank() && !summary.group(1).equalsIgnoreCase("null");
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
