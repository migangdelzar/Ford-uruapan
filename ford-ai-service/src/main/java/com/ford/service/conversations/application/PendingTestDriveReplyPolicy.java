package com.ford.service.conversations.application;

import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadNextAction;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Continues an explicit, single-option test-drive offer without model interpretation. */
public final class PendingTestDriveReplyPolicy {

    private static final Set<String> AFFIRMATIVE = Set.of(
            "si", "si me gustaria", "me gustaria", "si por favor", "claro", "claro que si",
            "por supuesto", "de acuerdo", "adelante", "va", "ok", "okay");
    private static final String NEXT_QUESTION = "¡Perfecto! ¿Qué día te funciona para la prueba de manejo?";

    public Optional<AssistantReply> replyFor(String userMessage, ConversationMemoryContext memory) {
        return replyFor(userMessage, memory, null);
    }

    public Optional<AssistantReply> replyFor(String userMessage, ConversationMemoryContext memory,
                                             LeadJourneySnapshot journey) {
        if (!AFFIRMATIVE.contains(normalize(userMessage))) return Optional.empty();
        var previousAssistantMessage = memory == null ? null : memory.recentMessages().stream()
                .filter(message -> message.direction() == ConversationMessage.Direction.OUTBOUND)
                .max(Comparator.comparing(ConversationMessage::occurredAt))
                .map(ConversationMessage::content)
                .orElse(null);
        var journeyExpectsConfirmation = journey != null
                && journey.currentTopic() == LeadJourneyTopic.TEST_DRIVE_APPOINTMENT
                && "TEST_DRIVE_CONFIRMATION".equals(journey.pendingQuestionKey());
        if (!journeyExpectsConfirmation && !isSingleOptionTestDriveOffer(previousAssistantMessage)) {
            return Optional.empty();
        }

        var decision = new ConversationDecision(ConversationIntent.APPOINTMENT, LeadTemperature.HOT,
                LeadStage.QUALIFIED, HandoffRecommendation.NONE, ToolFamily.APPOINTMENT,
                SensitiveDataLevel.NONE, LeadNextAction.SCHEDULE_TEST_DRIVE, true, userMessage, List.of(
                evidence(ClassifierType.INTENT, ConversationIntent.APPOINTMENT.name()),
                evidence(ClassifierType.LEAD, "QUALIFIED/HOT")));
        var proposal = new LeadJourneyProposal(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT,
                List.of(), "TEST_DRIVE_DATE");
        return Optional.of(new AssistantReply(NEXT_QUESTION, false, decision, proposal));
    }

    private static boolean isSingleOptionTestDriveOffer(String assistantMessage) {
        if (assistantMessage == null) return false;
        var questionStart = assistantMessage.lastIndexOf('¿');
        var question = normalize(questionStart >= 0 ? assistantMessage.substring(questionStart) : assistantMessage);
        var normalizedMessage = normalize(assistantMessage);
        return normalizedMessage.contains("prueba de manejo")
                && (question.contains("agendar") || question.contains("programar")
                || question.contains("gustaria") || question.contains("coordinar"))
                && !question.contains(" o ")
                && !question.contains("financiamiento")
                && !question.contains("versiones")
                && !question.contains("oferta")
                && count(assistantMessage, '¿') <= 1
                && count(assistantMessage, '?') <= 1;
    }

    private static ClassificationEvidence evidence(ClassifierType classifier, String value) {
        return new ClassificationEvidence(classifier, value, 1.0, "pending_question_confirmation",
                "rules", "deterministic", 0, true);
    }

    private static int count(String value, char target) {
        return (int) value.chars().filter(character -> character == target).count();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }
}
