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
import java.util.regex.Pattern;

@Component
public class LeadJourneyPolicy {

    private static final int MAX_FACT_LENGTH = 160;
    private static final int MAX_EVIDENCE_LENGTH = 300;
    private static final int MAX_PENDING_KEY_LENGTH = 80;
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Pattern CONTACT_OR_ACCOUNT = Pattern.compile(
            "(?i)([\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\+?\\d[\\s().-]*){8,}|\\b(?:tarjeta|cuenta|clabe|cvv|curp|rfc)\\b)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

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
            case CUSTOMER_NAME -> evidence.toLowerCase().contains(value.toLowerCase())
                    && Pattern.compile("(?i)\\b(me llamo|mi nombre es|soy)\\b").matcher(evidence).find();
            case DOWN_PAYMENT -> {
                var evidenceNumbers = numbers(evidence);
                yield !evidenceNumbers.isEmpty() && evidenceNumbers.equals(numbers(value));
            }
            case PAYMENT_METHOD -> {
                var valueLower = value.toLowerCase();
                var evidenceLower = evidence.toLowerCase();
                if (valueLower.matches(".*\\b(contado|efectivo)\\b.*")) {
                    yield Pattern.compile("(?i)\\b(contado|efectivo)\\b").matcher(evidenceLower).find();
                }
                if (valueLower.matches(".*\\b(financiamiento|financiar|cr[eé]dito|mensualidades)\\b.*")) {
                    yield Pattern.compile("(?i)\\b(financiamiento|financiar|cr[eé]dito|mensualidades)\\b").matcher(evidenceLower).find();
                }
                if (valueLower.matches(".*\\b(arrendamiento|leasing)\\b.*")) {
                    yield Pattern.compile("(?i)\\b(arrendamiento|leasing)\\b").matcher(evidenceLower).find();
                }
                yield false;
            }
            default -> true;
        };
    }

    private static java.util.List<String> numbers(String text) {
        var matcher = NUMBER.matcher(text);
        var result = new java.util.ArrayList<String>();
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static boolean hasSensitivePattern(String text) {
        return LONG_DIGIT_RUN.matcher(text).find() || CONTACT_OR_ACCOUNT.matcher(text).find();
    }
}
