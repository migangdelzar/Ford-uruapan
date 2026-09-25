package com.ford.service.ai.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic domain boundary for the Ford vehicle-shopping assistant. */
public final class AssistantScopePolicy {

    private static final List<String> ALLOWED_TERMS = List.of(
            "ford", "auto", "autos", "coche", "coches", "carro", "carros", "vehículo", "vehiculo",
            "camioneta", "ranger", "bronco", "maverick", "territory", "explorer", "mustang", "f-150",
            "lobo", "raptor", "pickup", "pick-up",
            "precio", "oferta", "promoción", "promocion", "bono", "modelo", "versión", "version", "motor",
            "opciones", "consumo", "rendimiento", "kilómetro", "kilometro", "km/l", "litro", "potencia",
            "caballos", "equipamiento", "seguridad", "tracción", "traccion", "detalles", "información", "informacion",
            "garantía", "garantia", "disponible", "inventario", "financiamiento", "financiación", "financiacion",
            "crédito", "credito", "enganche", "mensualidad", "cita", "asesor", "agencia", "sucursal",
            "horario", "teléfono", "telefono", "comprar", "prueba de manejo", "persona", "humano",
            "que me llame", "que me llamen");

    private static final List<String> GREETING_TERMS = List.of(
            "hola", "hi", "hello", "buenas", "buen día", "buen dia", "buenos días", "buenos dias", "gracias", "adiós", "adios");

    private static final List<String> CLEARLY_UNRELATED_TERMS = List.of(
            "poema", "escribe una canción", "escribe una cancion", "receta", "clima", "pronóstico del tiempo",
            "pronostico del tiempo", "capital de", "código para", "codigo para");

    private final IntentRouter intentRouter;

    public AssistantScopePolicy(IntentRouter intentRouter) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
    }

    public boolean allows(String userText, ConversationDecision decision) {
        return allows(userText, decision, null);
    }

    public boolean allows(String userText, ConversationDecision decision, String memoryContext) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        var normalized = userText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, CLEARLY_UNRELATED_TERMS)) {
            return false;
        }
        return containsAny(normalized, ALLOWED_TERMS) || containsAny(normalized, GREETING_TERMS)
                || intentRouter.route(userText) != Intent.GENERAL
                || isContextualFollowUp(normalized, memoryContext);
    }

    public String scopeResponse() {
        return "Con mucho gusto le ayudo con vehículos Ford, precios, ofertas, "
                + "financiamiento y citas. ¿Qué modelo le interesa revisar?";
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private static boolean isContextualFollowUp(String normalizedMessage, String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()
                || !containsAny(memoryContext.toLowerCase(Locale.ROOT),
                List.of("ford", "ranger", "lobo", "raptor", "bronco", "maverick", "territory", "explorer", "vehículo", "vehiculo"))) {
            return false;
        }
        return normalizedMessage.contains("?") || normalizedMessage.contains("¿")
                || containsAny(normalizedMessage, List.of("ayúdame", "ayudame", "dime", "dame", "más", "mas", "detalles", "información", "informacion"));
    }
}
