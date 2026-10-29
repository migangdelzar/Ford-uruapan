package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.observability.AiObservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ConversationWorkflow {
    private static final Logger log = LoggerFactory.getLogger(ConversationWorkflow.class);
    private static final Duration TURN_LEASE_DURATION = Duration.ofMinutes(15);

    private final ConversationGraphRunner graphRunner;
    private final AiObservationService observations;
    private final ConversationTurnLeasePort turnLeases;
    private final InboundDebounceGate debounceGate;
    private final ConversationTurnLedgerPort turnLedger;
    private final String leaseOwner = UUID.randomUUID().toString();

    public ConversationWorkflow(ConversationGraphRunner graphRunner, AiObservationService observations,
                                ConversationTurnLeasePort turnLeases, InboundDebounceGate debounceGate,
                                ConversationTurnLedgerPort turnLedger) {
        this.graphRunner = Objects.requireNonNull(graphRunner);
        this.observations = Objects.requireNonNull(observations);
        this.turnLeases = turnLeases;
        this.debounceGate = debounceGate;
        this.turnLedger = Objects.requireNonNull(turnLedger);
    }

    @ApplicationModuleListener
    @Async("conversationTurnExecutor")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void on(InboundMessageAccepted event) {
        dispatch(event, true);
    }

    @ApplicationModuleListener
    @Async("conversationTurnExecutor")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void on(InboundTurnReleased event) {
        dispatch(event.asInboundMessageAccepted(), false);
    }

    private void dispatch(InboundMessageAccepted event, boolean honorDebounce) {
        var started = System.nanoTime();
        var previousConversationId = MDC.get("conversationId");
        var previousReceiptId = MDC.get("receiptId");
        var previousProviderMessageId = MDC.get("providerMessageId");
        var previousStage = MDC.get("workflowStage");
        MDC.put("conversationId", event.conversationId().toString());
        MDC.put("receiptId", event.receiptId().toString());
        MDC.put("providerMessageId", event.providerMessageId());
        MDC.put("workflowStage", "RECEIVED");
        var leased = turnLeases == null;
        try {
            record(event, ConversationTurnState.ACCEPTED, null);
            if (turnLeases != null) {
                leased = turnLeases.tryAcquire(event.tenantId(), event.conversationId(), leaseOwner,
                        TURN_LEASE_DURATION);
                if (!leased) {
                    throw new ConversationTurnLeaseUnavailableException(event.conversationId());
                }
            }
            if (honorDebounce && debounceGate != null
                    && !debounceGate.isDue(event.tenantId(), event.conversationId(), event.receiptId())) {
                record(event, ConversationTurnState.DEBOUNCED, null);
                MDC.put("workflowStage", "DEBOUNCE_DEFERRED");
                log.info("Conversation workflow deferred for debounce: conversationId={} receiptId={}",
                        event.conversationId(), event.receiptId());
                return;
            }

            record(event, ConversationTurnState.PROCESSING, null);
            MDC.put("workflowStage", "PROCESSING");
            var result = graphRunner.run(new ConversationTurnReference(
                    event.tenantId(), event.conversationId(), event.receiptId()));
            MDC.put("workflowStage", result.route().name());
            log.info("Conversation workflow completed: conversationId={} route={} terminal={} durationMs={}",
                    event.conversationId(), result.route(), result.terminal(), elapsedMs(started));
            record(event, stateFor(result.route()),
                    result.route() == com.ford.service.conversations.application.graph.ConversationGraphRoute.NEEDS_OPERATOR
                            ? "inbound_or_turn_invariant" : null);
            observations.event("ford.conversation.inbound", Map.of(
                    "channel", event.channel().name(), "outcome", result.route().name()));
        } catch (RuntimeException failure) {
            var category = failureCategory(failure);
            record(event, ConversationTurnState.RETRY_WAIT, category);
            var safeRootType = failure instanceof com.ford.service.conversations.application.graph.ConversationGraphExecutionException graphFailure
                    ? graphFailure.rootCauseType() : failure.getClass().getSimpleName();
            log.error("Conversation workflow failed: conversationId={} receiptId={} failureCategory={} rootCauseType={} durationMs={}",
                    event.conversationId(), event.receiptId(), failureCategory(failure), safeRootType, elapsedMs(started));
            observations.event("ford.conversation.inbound", Map.of(
                    "channel", event.channel().name(), "outcome", "failure"));
            throw failure;
        } finally {
            if (turnLeases != null && leased) {
                turnLeases.release(event.tenantId(), event.conversationId(), leaseOwner);
            }
            restoreMdc("conversationId", previousConversationId);
            restoreMdc("receiptId", previousReceiptId);
            restoreMdc("providerMessageId", previousProviderMessageId);
            restoreMdc("workflowStage", previousStage);
        }
    }

    private void record(InboundMessageAccepted event, ConversationTurnState state, String failureCategory) {
        turnLedger.recordOutcome(event.tenantId(), event.conversationId(), event.receiptId(),
                event.providerMessageId(), state, failureCategory, java.time.Instant.now());
    }

    private static ConversationTurnState stateFor(
            com.ford.service.conversations.application.graph.ConversationGraphRoute route) {
        return switch (route) {
            case BOT_REPLY, WARM_FOLLOW_UP -> ConversationTurnState.REPLY_COMMITTED;
            case SAFE_FALLBACK_REVIEW -> ConversationTurnState.SAFE_FALLBACK_REVIEW;
            case HUMAN_PENDING -> ConversationTurnState.HUMAN_PENDING;
            case DUPLICATE -> ConversationTurnState.DUPLICATE;
            case OUT_OF_SCOPE -> ConversationTurnState.OUT_OF_SCOPE;
            case SHADOW_DRAFT -> ConversationTurnState.SHADOW_DRAFT;
            case NEEDS_OPERATOR -> ConversationTurnState.NEEDS_OPERATOR;
        };
    }

    private static String failureCategory(RuntimeException failure) {
        if (failure instanceof ConversationTurnLeaseUnavailableException) return "turn_lease_unavailable";
        if (failure instanceof com.ford.service.conversations.application.graph.ConversationGraphExecutionException graphFailure) {
            return graphFailure.failureCategory();
        }
        if (failure instanceof com.ford.service.ai.api.AssistantTurnUnavailableException unavailable) {
            return "assistant_unavailable_" + unavailable.disposition().name().toLowerCase(java.util.Locale.ROOT);
        }
        if (containsSqlFailure(failure)) return "database_failure";
        return "graph_failure";
    }

    private static boolean containsSqlFailure(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (var current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof java.sql.SQLException) return true;
        }
        return false;
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
