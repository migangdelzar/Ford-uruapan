package com.ford.service.ai.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic domain boundary for the Ford vehicle-shopping assistant. */
public class AssistantScopePolicy {

    private static final List<String> GREETING_TERMS = List.of(
            "hola", "hola buen dia", "hola buenos dias", "hi", "hello", "buenas", "buen dia", "buenos dias",
            "gracias", "muchas gracias", "adios");

    private static final List<String> CLEARLY_UNRELATED_TERMS = List.of(
            "poema", "escribe una cancion", "receta", "clima", "pronostico del tiempo",
            "capital de", "capital of", "teoria de la relatividad", "hackear", "hack account", "codigo para",
            "write a poem", "poem", "recipe", "weather", "write a song", "medical diagnosis");

    private static final List<String> CONTEXTUAL_FOLLOW_UPS = List.of(
            "y cuanto", "y que precio", "y el precio", "cuanto cuesta", "cuanto vale", "explicame eso",
            "explicame mejor", "que incluye", "que opciones", "mas informacion", "y esa version",
            "y ese modelo", "la otra version", "el otro modelo", "que version me recomienda",
            "que modelo me recomienda");
    private static final List<String> VEHICLE_TRIM_FOLLOW_UPS = List.of(
            "xl", "xlt", "lariat", "limited", "king ranch", "platinum", "tremor", "active", "titanium",
            "trend", "ambiente", "wildtrak", "badlands", "outer banks", "raptor", "max");

    private static final Set<Intent> SUPPORTED_FORD_INTENTS = Set.of(
            Intent.INVENTORY, Intent.CATALOG, Intent.OFFERS, Intent.FINANCE, Intent.KNOWLEDGE,
            Intent.LEAD_HANDOFF, Intent.APPOINTMENT, Intent.TRADE_IN, Intent.DEALERSHIP_INFO);

    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}/-]+");

    private final IntentRouter intentRouter;
    private final Set<String> availableReadOnlyTools;

    public AssistantScopePolicy(IntentRouter intentRouter) {
        this(intentRouter, Set.of());
    }

    public AssistantScopePolicy(IntentRouter intentRouter, Set<String> availableReadOnlyTools) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
        this.availableReadOnlyTools = Objects.requireNonNull(availableReadOnlyTools).stream()
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean allows(String userText, ConversationDecision decision) {
        return allows(userText, decision, null);
    }

    public boolean allows(String userText, ConversationDecision decision, String memoryContext) {
        return "allowed".equals(reason(userText, decision, memoryContext));
    }

    /** Returns a content-free decision code for safe diagnostics. */
    public String reason(String userText, ConversationDecision decision, String memoryContext) {
        if (userText == null || userText.isBlank()) {
            return "blank_input";
        }
        var normalized = normalize(userText);
        var routedIntent = intentRouter.route(userText);
        if (decision != null && decision.intent() == ConversationIntent.OUT_OF_SCOPE) {
            return "classifier_out_of_scope";
        }
        if (routedIntent == Intent.OUT_OF_SCOPE) {
            return "rules_out_of_scope";
        }
        if (containsAny(normalized, CLEARLY_UNRELATED_TERMS)) {
            return "clearly_unrelated_request";
        }
        if (SUPPORTED_FORD_INTENTS.contains(routedIntent)) {
            return "allowed";
        }
        if (routedIntent == Intent.SERVICE_SUPPORT) {
            return availableReadOnlyTools.contains("approved_knowledge_search")
                    ? "allowed" : "service_knowledge_tool_unavailable";
        }
        if (decision != null && SUPPORTED_FORD_INTENTS.contains(decision.toLegacyIntent())) {
            return isFordContextualFollowUp(normalized, memoryContext)
                    ? "allowed" : "classified_intent_not_contextual_followup";
        }
        if (routedIntent != Intent.GENERAL) {
            return "unsupported_routed_intent";
        }
        if (isGreeting(normalized) || isFordContextualFollowUp(normalized, memoryContext)) {
            return "allowed";
        }
        return hasFordContext(memoryContext)
                ? "allowed"
                : "general_without_ford_context";
    }

    public String scopeResponse() {
        return "Con mucho gusto le ayudo con vehículos Ford, precios, ofertas, "
                + "financiamiento y citas. ¿Qué modelo le interesa revisar?";
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private static boolean isGreeting(String normalized) {
        return GREETING_TERMS.contains(normalized);
    }

    private static String normalize(String value) {
        var withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return PUNCTUATION.matcher(withoutAccents.toLowerCase(Locale.ROOT))
                .replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    private static boolean hasFordContext(String memoryContext) {
        return memoryContext != null && !memoryContext.isBlank()
                && containsAny(memoryContext.toLowerCase(Locale.ROOT),
                List.of("ford", "ranger", "lobo", "raptor", "bronco", "maverick", "territory", "explorer", "vehículo", "vehiculo"));
    }

    private static boolean isFordContextualFollowUp(String normalized, String memoryContext) {
        if (!hasFordContext(memoryContext)) return false;
        return containsAny(normalized, CONTEXTUAL_FOLLOW_UPS)
                || normalized.length() <= 40 && containsAny(normalized, VEHICLE_TRIM_FOLLOW_UPS);
    }
}
