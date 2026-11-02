package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Enforces customer authorization from persisted conversation turns, independently of model output. */
public final class CustomerHandoffAuthorizationPolicy {
    private static final Pattern DIRECT_REQUEST = Pattern.compile(
            "\\b(hablar|comunicarme|conect(?:ar|arme|e|es|ame)|contactarme|ponerme en contacto|pasarme|transferirme)\\b.{0,70}\\b(asesor|asesora|especialista|persona|humano)\\b"
                    + "|\\b(asesor|asesora|especialista|persona|humano)\\b.{0,70}\\b(hablar|comunicarme|conect(?:ar|arme|e|es|ame)|contactarme|ponerme en contacto|pasarme|transferirme)\\b");
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^(si|claro|de acuerdo|acepto|por supuesto|adelante|va|ok|okay)(?:[,. ]+(quiero hablar|quiero conectar|quiero que me conectes|quiero que me comuniques|conectame|conectarme).*)?[!. ]*$");
    private static final Pattern HANDOFF_OFFER = Pattern.compile(
            "\\b(asesor|asesora|especialista|persona|humano)\\b.{0,90}\\b(conect\\w*|comunic\\w*|hablar|contactar|ayudar|atender|ponerme en contacto)\\b"
                    + "|\\b(conect\\w*|comunic\\w*|hablar|contactar|ayudar|atender)\\b.{0,90}\\b(asesor|asesora|especialista|persona|humano)\\b");

    public boolean isAuthorized(String currentUserMessage, ConversationMemoryContext memory) {
        if (currentUserMessage == null || currentUserMessage.isBlank()) return false;
        var current = normalize(currentUserMessage);
        if (isDecline(currentUserMessage)) return false;
        if (isDirectRequest(currentUserMessage)) return true;
        if (!AFFIRMATIVE.matcher(current).matches() || memory == null || memory.recentMessages().isEmpty()) return false;

        var turns = memory.recentMessages();
        if (turns.isEmpty()) return false;
        var precedingIndex = turns.size() - 1;
        var lastTurn = turns.get(precedingIndex);
        // JDBC memory intentionally excludes the current inbound turn; test and cache ports may include it.
        if (lastTurn.direction() == ConversationMessage.Direction.INBOUND
                && normalize(lastTurn.content()).equals(current)) precedingIndex--;
        if (precedingIndex < 0) return false;
        var precedingTurn = turns.get(precedingIndex);
        return precedingTurn.direction() == ConversationMessage.Direction.OUTBOUND
                && HANDOFF_OFFER.matcher(normalize(precedingTurn.content())).find();
    }

    public boolean isDecline(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        var current = normalize(userMessage);
        return java.util.Set.of("no", "no gracias", "no por ahora", "prefiero que no", "mejor no",
                "ahora no", "por ahora no", "sin asesor", "sin especialista").contains(current)
                || current.matches("no (?:gracias|por ahora|mejor no|prefiero que no|prefiero seguir(?: por)? aqui)")
                || current.matches(".*\\bno quiero (?:hablar|comunicarme|conectarme|que me conecten)\\b.*")
                || current.matches(".*\\bno (?:quiero|deseo|quisiera) (?:hablar|conectar|contactar|que me conecten)\\b.*")
                || current.matches(".*\\bno quiero (?:un )?(?:asesor|asesora|especialista|persona|humano)\\b.*")
                || current.matches("no me interesa(?: hablar| conectar)? con (?:un )?(?:asesor|especialista)");
    }

    public boolean isDirectRequest(String userMessage) {
        return userMessage != null && !userMessage.isBlank()
                && !isDecline(userMessage)
                && DIRECT_REQUEST.matcher(normalize(userMessage)).find();
    }

    public boolean isAssistantSpecialistOffer(String assistantMessage) {
        return assistantMessage != null && HANDOFF_OFFER.matcher(normalize(assistantMessage)).find();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(Objects.requireNonNullElse(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }
}
