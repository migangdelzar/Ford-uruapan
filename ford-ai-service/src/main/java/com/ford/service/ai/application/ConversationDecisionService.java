package com.ford.service.ai.application;

import com.ford.service.ai.configuration.ClassificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

public class ConversationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDecisionService.class);

    private final ConversationDecisionPort primary;
    private final ConversationDecisionPort rules;
    private final ClassificationProperties properties;
    private final IntentRouter intentRouter = new IntentRouter();
    private final VehicleEntityResolver vehicleEntityResolver = new VehicleEntityResolver();
    private final ConversationFollowUpResolver followUpResolver = new ConversationFollowUpResolver();

    public ConversationDecisionService(ConversationDecisionPort primary,
                                       ConversationDecisionPort rules,
                                       ClassificationProperties properties) {
        this.primary = Objects.requireNonNull(primary);
        this.rules = Objects.requireNonNull(rules);
        this.properties = Objects.requireNonNull(properties);
    }

    public boolean hasConfidentLeadEvidence(ConversationDecision decision) {
        return decision.evidence().stream().anyMatch(evidence ->
                evidence.classifier() == ClassifierType.LEAD
                        && !evidence.fallbackUsed()
                        && evidence.confidence() >= properties.minConfidence());
    }

    public ConversationDecision decide(ClassificationContext context) {
        Objects.requireNonNull(context, "classification context is required");
        if (context.containsSensitiveData()) {
            log.info("Conversation classification using deterministic rules: conversationId={} reason=sensitive_data",
                    context.conversationId());
            return withEntities(rules.decide(context), context);
        }
        if (intentRouter.isCourtesyOnly(context.message())) {
            var currentTurnOnly = new ClassificationContext(context.tenantId(), context.conversationId(),
                    context.message(), null, context.conversationState(), context.containsSensitiveData());
            return rules.decide(currentTurnOnly);
        }
        var deterministicRoute = rules.decide(context);
        var followUp = followUpResolver.resolve(context.message(), context.memoryContext());
        if (followUp.isPresent()) {
            var resolved = followUp.get();
            var route = new ConversationDecision(resolved.intent(), deterministicRoute.leadTemperature(),
                    deterministicRoute.leadStage(), deterministicRoute.handoffRecommendation(), resolved.toolFamily(),
                    deterministicRoute.sensitiveDataLevel(), deterministicRoute.nextAction(),
                    deterministicRoute.urgencySignal(), deterministicRoute.summary(), deterministicRoute.evidence());
            return deterministicRoute.withDeterministicRoute(route)
                    .withVehicleEntities(resolved.vehicleEntities()).withSummary(resolved.pendingAction());
        }
        if (intentRouter.isAssistantVehicleReferenceConfirmation(context.message(), context.memoryContext())) {
            var route = new ConversationDecision(ConversationIntent.GENERAL,
                    deterministicRoute.leadTemperature(), deterministicRoute.leadStage(),
                    deterministicRoute.handoffRecommendation(), ToolFamily.NONE,
                    deterministicRoute.sensitiveDataLevel(), deterministicRoute.nextAction(),
                    deterministicRoute.urgencySignal(), deterministicRoute.summary(), deterministicRoute.evidence());
            return withEntities(deterministicRoute.withDeterministicRoute(route), context);
        }
        if (intentRouter.isExplicitFordFactFollowUp(context.message(), context.memoryContext())) {
            var route = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                    deterministicRoute.leadTemperature(), deterministicRoute.leadStage(),
                    deterministicRoute.handoffRecommendation(), ToolFamily.VEHICLE_DETAILS,
                    deterministicRoute.sensitiveDataLevel(), deterministicRoute.nextAction(),
                    deterministicRoute.urgencySignal(), deterministicRoute.summary(), deterministicRoute.evidence());
            log.info("Conversation classification using deterministic rules: conversationId={} reason=ford_fact_follow_up toolFamily={}",
                    context.conversationId(), route.toolFamily());
            return withEntities(route, context);
        }
        if (intentRouter.isExplicitFordFactRequest(context.message())) {
            log.info("Conversation classification using deterministic rules: conversationId={} reason=explicit_ford_fact_request intent={} toolFamily={}",
                    context.conversationId(), deterministicRoute.intent(), deterministicRoute.toolFamily());
            return withEntities(deterministicRoute, context);
        }
        if (!properties.enabled() || "disabled".equalsIgnoreCase(properties.mode())) {
            return withEntities(deterministicRoute, context);
        }
        try {
            var classified = primary.decide(context);
            if ("shadow".equalsIgnoreCase(properties.mode())) {
                return withEntities(deterministicRoute, context);
            }
            if (classified.evidence().stream()
                    .anyMatch(evidence -> !evidence.fallbackUsed()
                            && evidence.confidence() < properties.minConfidence())) {
                throw new IllegalStateException("TypeSafe classification confidence is below the configured threshold");
            }
            var route = deterministicRoute;
            var contextHasFordVehicle = context.memoryContext() != null
                    && context.memoryContext().toLowerCase(Locale.ROOT).matches("(?s).*(ford|ranger|bronco|maverick|lobo|raptor|territory|explorer).*" );
            var deterministicScopeDeniesTools = route.toolFamily() == ToolFamily.NONE
                    && (route.intent() == ConversationIntent.OUT_OF_SCOPE
                    || route.intent() == ConversationIntent.GENERAL && (!contextHasFordVehicle
                    || intentRouter.isPrePurchaseCriteriaQuestion(context.message())));
            var deterministicIntentHasSpecificTool = switch (route.intent()) {
                case INVENTORY, VEHICLE_DETAILS, FINANCE, OFFERS, APPOINTMENT, SERVICE_SUPPORT -> true;
                case GENERAL, KNOWLEDGE, HUMAN_REQUEST, TRADE_IN, DEALERSHIP_INFO,
                        COMPLAINT_STATUS, OUT_OF_SCOPE -> false;
            };
            if (route.toolFamily() != classified.toolFamily()
                    && (deterministicIntentHasSpecificTool || deterministicScopeDeniesTools)) {
                log.warn("Conversation tool route reconciled: conversationId={} classifiedIntent={} classifiedToolFamily={} deterministicIntent={} deterministicToolFamily={}",
                        context.conversationId(), classified.intent(), classified.toolFamily(),
                        route.intent(), route.toolFamily());
                return withEntities(classified.withDeterministicRoute(route), context);
            }
            return withEntities(classified, context);
        } catch (RuntimeException ignored) {
            log.warn("Conversation classification failed; using rules fallback: exceptionType={}",
                    ignored.getClass().getSimpleName());
            return withEntities(deterministicRoute, context);
        }
    }

    private ConversationDecision withEntities(ConversationDecision decision, ClassificationContext context) {
        var entities = vehicleEntityResolver.resolve(context.message(), context.memoryContext());
        if (entities.status() == VehicleEntityResolution.Status.MISSING
                && entities.model().isEmpty() && entities.modelYear().isEmpty() && entities.trim().isEmpty()
                && entities.comparisonYears().isEmpty()) {
            return decision;
        }
        return decision.withVehicleEntities(entities);
    }
}
