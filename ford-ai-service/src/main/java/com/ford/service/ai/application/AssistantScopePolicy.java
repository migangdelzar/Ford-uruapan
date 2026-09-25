package com.ford.service.ai.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic domain boundary for the Ford vehicle-shopping assistant. */
public final class AssistantScopePolicy {

    private static final List<String> ALLOWED_TERMS = List.of(
            "ford", "auto", "autos", "coche", "coches", "carro", "carros", "vehículo", "vehiculo",
            "camioneta", "ranger", "bronco", "maverick", "territory", "explorer", "mustang", "f-150",
            "precio", "oferta", "promoción", "promocion", "bono", "modelo", "versión", "version", "motor",
            "opciones",
            "garantía", "garantia", "disponible", "inventario", "financiamiento", "financiación", "financiacion",
            "crédito", "credito", "enganche", "mensualidad", "cita", "asesor", "agencia", "sucursal",
            "horario", "teléfono", "telefono", "comprar", "prueba de manejo", "persona", "humano",
            "que me llame", "que me llamen");

    private static final List<String> GREETING_TERMS = List.of(
            "hola", "buenas", "buen día", "buen dia", "buenos días", "buenos dias", "gracias", "adiós", "adios");

    private final IntentRouter intentRouter;

    public AssistantScopePolicy(IntentRouter intentRouter) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
    }

    public boolean allows(String userText, ConversationDecision decision) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        var normalized = userText.toLowerCase(Locale.ROOT);
        return containsAny(normalized, ALLOWED_TERMS) || containsAny(normalized, GREETING_TERMS)
                || intentRouter.route(userText) != Intent.GENERAL;
    }

    public String scopeResponse() {
        return "Con mucho gusto le ayudo con vehículos Ford, precios, ofertas, "
                + "financiamiento y citas. ¿Qué modelo le interesa revisar?";
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }
}
