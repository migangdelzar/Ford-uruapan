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
            "y cuanto", "y que precio", "y el precio", "cuanto cuesta", "explicame eso", "explicame mejor",
            "que incluye", "que opciones", "mas informacion", "y esa version", "y ese modelo", "la otra version",
            "el otro modelo");

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
        if (userText == null || userText.isBlank()) {
            return false;
        }
        var normalized = normalize(userText);
        var routedIntent = intentRouter.route(userText);
        if ((decision != null && decision.intent() == ConversationIntent.OUT_OF_SCOPE)
                || routedIntent == Intent.OUT_OF_SCOPE) {
            return false;
        }
        if (containsAny(normalized, CLEARLY_UNRELATED_TERMS)) {
            return false;
        }
        if (SUPPORTED_FORD_INTENTS.contains(routedIntent)) {
            return true;
        }
        if (routedIntent == Intent.SERVICE_SUPPORT
                && availableReadOnlyTools.contains("approved_knowledge_search")) {
            return true;
        }
        if (routedIntent != Intent.GENERAL) {
            return false;
        }
        return isGreeting(normalized)
                || hasFordContext(memoryContext) && containsAny(normalized, CONTEXTUAL_FOLLOW_UPS);
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
}
