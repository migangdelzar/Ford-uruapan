package com.ford.service.conversations.application;

import java.text.Normalizer;
import java.util.Locale;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;

/** Identifies turns that should offer specialist help without initiating a handoff. */
public final class CustomerHandoffOfferPolicy {
    public static final String OFFER = "¿Desea que le conecte con un especialista Ford?";
    private final CustomerHandoffAuthorizationPolicy authorization = new CustomerHandoffAuthorizationPolicy();

    public boolean shouldOffer(String currentUserMessage, String assistantAnswer, String answer) {
        if (currentUserMessage == null || currentUserMessage.isBlank()
                || authorization.isDirectRequest(currentUserMessage)) return false;
        var message = normalize(currentUserMessage);
        var purchase = hasAny(message, "comprar", "cotizar", "cotización formal", "apartarlo", "auto a cuenta")
                && hasAny(message, "quiero", "quisiera", "me interesa", "estoy listo", "estoy lista",
                "listo para", "lista para", "necesito", "deseo", "dispuesto a", "dispuesta a");
        var appointment = (message.contains("prueba de manejo") || message.contains("cita"))
                && hasAny(message, "quiero", "quisiera", "me interesa", "me gustaría", "agendar", "reservar");
        return purchase || appointment || hasUnresolvedOfferQuestion(answer);
    }

    public boolean shouldOffer(String currentUserMessage, String answer) {
        return shouldOffer(currentUserMessage, null, answer);
    }

    public boolean shouldOffer(String currentUserMessage, String assistantAnswer,
                               LeadTemperature leadTemperature, ConversationMemoryContext memory) {
        if (wasDeclined(memory)) return false;
        return leadTemperature == LeadTemperature.HOT
                || shouldOffer(currentUserMessage, assistantAnswer, null);
    }

    private boolean wasDeclined(ConversationMemoryContext memory) {
        if (memory == null) return false;
        if (memory.durableFacts().stream().anyMatch(fact ->
                "specialist_offer_declined".equals(fact.kind()))) return true;
        return memory.recentMessages().stream()
                .filter(message -> message.direction() == ConversationMessage.Direction.INBOUND)
                .anyMatch(message -> authorization.isDecline(message.content()));
    }

    public String offerAfter(String answer) {
        if (authorization.isAssistantSpecialistOffer(answer)) return answer;
        return answer == null || answer.isBlank() ? OFFER : answer.strip() + "\n" + OFFER;
    }

    private static boolean hasUnresolvedOfferQuestion(String answer) {
        var normalized = normalize(answer);
        return normalized.contains("la vigencia no esta confirmada")
                || normalized.contains("plazo registrado") && normalized.contains("plazo")
                || normalized.contains("los plazos difieren")
                || normalized.contains("no coincide") && normalized.contains("plazo")
                || normalized.contains("difiere") && normalized.contains("plazo")
                || normalized.contains("discrepancia") && normalized.contains("plazo")
                || normalized.contains("no se pudo confirmar")
                || normalized.contains("falta confirmar");
    }

    private static boolean hasAny(String text, String... phrases) {
        return java.util.Arrays.stream(phrases).anyMatch(text::contains);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
