package com.ford.service.shared;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic final response repeat check. Never exposes compared content in its result. */
public final class AdvisorResponseRepeatPolicy {

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> BOILERPLATE = Set.of(
            "hola", "buenos", "buenas", "gracias", "gusto", "mucho", "con", "le", "les", "ayudo",
            "ayudar", "apoyo", "apoyarle", "apoyarlo", "favor", "desea", "quisiera", "algo", "mas",
            "si", "gusta", "estoy", "para", "usted", "ustedes", "claro", "perfecto", "excelente",
            "ford", "agencia", "compartir", "comparto", "informacion", "detalles");
    private static final double OVERLAP_THRESHOLD = 0.80;

    public Verdict evaluate(String currentMessage, List<String> recentAssistantReplies, String candidate) {
        return evaluate(currentMessage, List.of(), recentAssistantReplies, candidate);
    }

    public Verdict evaluate(String currentMessage, List<String> recentCustomerMessages,
                            List<String> recentAssistantReplies, String candidate) {
        var normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.isEmpty()) return new Verdict(false, "empty_candidate");
        if (sameQuestion(currentMessage, recentCustomerMessages)) return new Verdict(false, "same_question");
        if (recentAssistantReplies == null) return new Verdict(false, "no_recent_replies");
        var candidateTokens = contentTokens(normalizedCandidate);
        for (var priorReply : recentAssistantReplies) {
            var normalizedPrior = normalize(priorReply);
            if (normalizedPrior.isEmpty()) continue;
            if (normalizedPrior.equals(normalizedCandidate)) return new Verdict(true, "exact_repeat");
            var priorTokens = contentTokens(normalizedPrior);
            if (candidateTokens.size() < 3 || priorTokens.size() < 3) continue;
            var intersection = candidateTokens.stream().filter(priorTokens::contains).count();
            var overlap = intersection / (double) Math.max(candidateTokens.size(), priorTokens.size());
            if (overlap >= OVERLAP_THRESHOLD) return new Verdict(true, "near_repeat");
        }
        return new Verdict(false, "distinct_response");
    }

    private static boolean sameQuestion(String currentMessage, List<String> priorCustomerMessages) {
        var current = normalize(currentMessage);
        if (current.isEmpty() || priorCustomerMessages == null) return false;
        return priorCustomerMessages.stream().filter(message -> message != null && !message.isBlank())
                .map(AdvisorResponseRepeatPolicy::normalize).anyMatch(current::equals);
    }

    private static Set<String> contentTokens(String normalized) {
        return Arrays.stream(normalized.split("\\s+"))
                .filter(token -> token.length() > 1 && !BOILERPLATE.contains(token))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        if (value == null) return "";
        var withoutDiacritics = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return TOKEN_SEPARATOR.matcher(withoutDiacritics).replaceAll(" ").toLowerCase(Locale.ROOT).strip();
    }

    public record Verdict(boolean repeated, String reasonCode) { }
}
