package com.ford.service.ai.application;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small provider-neutral contract for responses that are safe and useful to
 * send to a customer. It deliberately does not attempt to judge factual truth;
 * tools and the system prompt remain responsible for grounding.
 */
public final class AssistantResponseQualityPolicy {

    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SECOND_DETAIL = Pattern.compile(
            "\\b(?:y|o)\\s+(?:que|cual|cuanto|cuantos|cuantas|donde|cuando|como|quien|a que)\\b");
        private static final List<String> INTERNAL_MARKERS = List.of(
            "search_vehicles", "search_vehicle_and_offers", "get_vehicle", "compare_vehicles",
            "search_offers", "list_promotions",
            "get_vehicle_provenance", "get_catalog_status", "vehicle_snapshot_search", "approved_knowledge_search",
            "get_page_links", "compare_trims", "compare_model_years", "get_variant_comparisons",
            "tool_call", "function_call", "openrouter", "ollama", "typesafe", "spring ai");
    private static final List<String> STRUCTURED_MARKERS = List.of(
            "\"query\"", "\"modelyear\"", "\"arguments\"", "\"tool\"", "\"function\"", "```json");
    private static final String GENERIC_SCOPE_FRAGMENT = "qué modelo le interesa revisar";
    private static final int MAX_RESPONSE_CHARS = 1_600;

    public Verdict evaluate(String currentMessage, String memory, String response) {
        if (response == null || response.isBlank()) {
            return rejected("empty_response");
        }
        if (response.length() > MAX_RESPONSE_CHARS) {
            return rejected("response_too_long");
        }
        var normalizedResponse = normalize(response);
        if (looksLikeInternalPayload(response)) {
            return rejected("internal_payload");
        }
        if (normalizedResponse.contains(normalize(GENERIC_SCOPE_FRAGMENT))
                && currentMessage != null && !currentMessage.isBlank()
                && !isGreeting(currentMessage)) {
            return rejected("generic_scope_response");
        }
        if (asksForMultipleDetails(response)) {
            return rejected("multiple_questions");
        }
        return new Verdict(true, "accepted");
    }

    public String repairInstruction(String reason) {
        return switch (reason == null ? "" : reason) {
            case "internal_payload" ->
                    "Responda directamente al cliente en español de México. No muestre JSON, argumentos, nombres de herramientas, proveedores ni razonamiento interno.";
            case "repeated_response" ->
                    "Redacte una respuesta nueva que avance la conversación y responda exactamente el último mensaje. No repita la respuesta anterior.";
            case "generic_scope_response" ->
                    "Responda la pregunta actual. No use una respuesta genérica de alcance ni vuelva a preguntar qué modelo interesa si ya existe una pregunta concreta.";
            case "multiple_questions" ->
                    "Responda directamente y solicite solo un dato para avanzar, con una sola pregunta. No combine dos datos ni ofrezca un menú.";
            case "response_too_long" ->
                    "Redacte una respuesta breve, directa y natural en español de México. Mantenga la respuesta dentro del límite indicado.";
            default ->
                    "Entregue una respuesta breve, natural y útil en español de México a la pregunta actual.";
        };
    }

    public String recoveryMessage(String reason) {
        return switch (reason == null ? "" : reason) {
            case "repeated_response" ->
                    "No quiero repetirle la respuesta anterior. ¿Qué detalle desea que revise?";
            case "internal_payload" ->
                    "No pude preparar una respuesta clara con la información disponible. Intente de nuevo en unos segundos.";
            case "empty_response" ->
                    "No pude responderle en este momento. Intente de nuevo en unos segundos.";
            default ->
                    "No pude generar una respuesta útil en este momento. Intente de nuevo en unos segundos.";
        };
    }

    /** Stable, content-free reason suitable for logs and metrics. */
    public String diagnosticReason(Verdict verdict) {
        return verdict == null || verdict.accepted() ? "accepted" : verdict.reason();
    }

    private static boolean looksLikeInternalPayload(String rawResponse) {
        var raw = rawResponse.toLowerCase(Locale.ROOT);
        var trimmedRaw = raw.strip();
        if ((trimmedRaw.startsWith("{") && trimmedRaw.endsWith("}"))
                || (trimmedRaw.startsWith("[") && trimmedRaw.endsWith("]"))) {
            return true;
        }
        if (INTERNAL_MARKERS.stream().anyMatch(raw::contains)
                || STRUCTURED_MARKERS.stream().anyMatch(raw::contains)) {
            return true;
        }
        return false;
    }

    private static boolean asksForMultipleDetails(String response) {
        var questionStarts = response.chars().filter(character -> character == '¿').count();
        if (questionStarts > 1) return true;
        if (questionStarts == 0) return false;
        var questionStart = response.lastIndexOf('¿');
        var questionEnd = response.indexOf('?', questionStart);
        if (questionEnd < 0) questionEnd = response.length();
        var question = normalize(response.substring(questionStart, questionEnd));
        return SECOND_DETAIL.matcher(question).find();
    }

    private static Set<String> words(String value) {
        return new LinkedHashSet<>(Arrays.stream(value.split("\\s+"))
                .filter(word -> !word.isBlank())
                .toList());
    }

    private static boolean isGreeting(String message) {
        var normalized = normalize(message);
        return normalized.matches("(hola|hi|hello|buenas|buenos dias|buen dia|gracias)[!. ]*");
    }

    private static String normalize(String value) {
        return WORDS.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", ""))
                .replaceAll(" ")
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    private static Verdict rejected(String reason) {
        return new Verdict(false, reason);
    }

    public record Verdict(boolean accepted, String reason) {
    }
}
