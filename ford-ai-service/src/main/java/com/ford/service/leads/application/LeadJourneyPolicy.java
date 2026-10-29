package com.ford.service.leads.application;

import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.leads.domain.ProposedLeadJourneyFact;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class LeadJourneyPolicy {

    private static final int MAX_FACT_LENGTH = 160;
    private static final int MAX_EVIDENCE_LENGTH = 300;
    private static final int MAX_PENDING_KEY_LENGTH = 80;
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Pattern CONTACT_OR_ACCOUNT = Pattern.compile(
            "(?i)([\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\+?\\d[\\s().-]*){8,}|\\b(?:tarjeta|cuenta|clabe|cvv|curp|rfc)\\b)");
    private static final Pattern PAYMENT_BUDGET = Pattern.compile(
            "(?i)\\b(mensualidad(?:es)?|mensual|al mes|por mes|pagar|pago|enganche|inicial|cr[eé]dito|financiar|cuota(?:s)?|semanal|por semana|cada semana|quincenal|quincena|por quincena|cada quincena|cada 15 d[ií]as|catorcenal|por catorcena)\\b");
    private static final Pattern PURCHASE_PRICE_PREFERENCE = Pattern.compile(
            "(?i)\\b(presupuesto\\s+(?:total|para comprar|de compra|para (?:el\\s+)?(?:auto|coche|veh[ií]culo|camioneta))|precio\\s+(?:de compra|del?\\s+(?:auto|coche|veh[ií]culo|camioneta)))\\b");

    public LeadJourneySnapshot apply(LeadJourneySnapshot current,
                                     LeadJourneyProposal proposal,
                                     String currentInboundMessage,
                                     boolean sensitiveDataDetected,
                                     String providerMessageId,
                                     Instant now) {
        if (current == null || proposal == null || now == null) {
            return current;
        }
        if (proposal.pendingQuestionKey() != null
                && (proposal.pendingQuestionKey().isBlank()
                || proposal.pendingQuestionKey().length() > MAX_PENDING_KEY_LENGTH)) {
            return current;
        }

        var currentTopic = proposal.currentTopic() == null ? current.currentTopic() : proposal.currentTopic();
        var visited = current.visitedTopics().isEmpty()
                ? EnumSet.noneOf(LeadJourneyTopic.class) : EnumSet.copyOf(current.visitedTopics());
        var paused = current.pausedTopics().isEmpty()
                ? EnumSet.noneOf(LeadJourneyTopic.class) : EnumSet.copyOf(current.pausedTopics());
        if (currentTopic != null && currentTopic != current.currentTopic()) {
            if (current.currentTopic() != null) {
                paused.add(current.currentTopic());
            }
            visited.add(currentTopic);
            paused.remove(currentTopic);
        }

        var facts = new HashMap<>(current.facts());
        if (!sensitiveDataDetected && currentInboundMessage != null) {
            for (ProposedLeadJourneyFact proposed : proposal.facts()) {
                if (isValid(proposed, currentInboundMessage)) {
                    facts.put(proposed.key(), new LeadJourneyFact(
                            proposed.value().strip(), providerMessageId, now));
                }
            }
        }

        var pendingQuestion = proposal.pendingQuestionKey();
        var attempts = pendingQuestion == null ? 0
                : pendingQuestion.equals(current.pendingQuestionKey()) ? current.pendingQuestionAttempts() + 1 : 1;
        return new LeadJourneySnapshot(current.tenantId(), current.conversationId(), current.leadId(),
                currentTopic, visited, paused, facts, pendingQuestion, attempts, providerMessageId,
                current.version(), now);
    }

    private static boolean isValid(ProposedLeadJourneyFact fact, String message) {
        if (fact == null || fact.key() == null || fact.value() == null || fact.exactUserEvidence() == null) {
            return false;
        }
        var value = fact.value().strip();
        var evidence = fact.exactUserEvidence();
        return !value.isEmpty() && value.length() <= MAX_FACT_LENGTH
                && !evidence.isBlank() && evidence.length() <= MAX_EVIDENCE_LENGTH
                && message.contains(evidence)
                && !hasSensitivePattern(value) && !hasSensitivePattern(evidence)
                && isSupportedPacketFact(fact.key(), value, evidence);
    }

    private static boolean isSupportedPacketFact(LeadJourneyFactKey key, String value, String evidence) {
        return switch (key) {
            case CUSTOMER_NAME, DOWN_PAYMENT, PAYMENT_METHOD -> false;
            case BUDGET_RANGE -> evidence.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))
                    && !PAYMENT_BUDGET.matcher(evidence).find()
                    && PURCHASE_PRICE_PREFERENCE.matcher(evidence).find();
            default -> true;
        };
    }

    private static boolean hasSensitivePattern(String text) {
        return LONG_DIGIT_RUN.matcher(text).find() || CONTACT_OR_ACCOUNT.matcher(text).find();
    }
}
