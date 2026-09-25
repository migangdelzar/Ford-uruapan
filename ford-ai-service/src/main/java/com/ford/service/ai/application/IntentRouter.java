package com.ford.service.ai.application;

import java.util.Locale;

public final class IntentRouter {

    public Intent route(String text) {
        if (text == null || text.isBlank()) {
            return Intent.GENERAL;
        }
        var normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "asesor", "asesora", "humano", "que me llame", "quiero comprar")) {
            return Intent.LEAD_HANDOFF;
        }
        if (containsAny(normalized, "disponible", "inventario", "stock", "unidades", "color")) {
            return Intent.INVENTORY;
        }
        if (containsAny(normalized, "mensualidad", "financiamiento", "comisión", "credito", "crédito", "enganche")) {
            return Intent.FINANCE;
        }
        if (containsAny(normalized, "promoción", "promocion", "oferta", "bono")) {
            return Intent.OFFERS;
        }
        if (containsAny(normalized, "garantía", "garantia", "especificación", "especificaciones", "motor")) {
            return Intent.KNOWLEDGE;
        }
        if (containsAny(normalized, "precio", "modelo", "version", "versión", "ranger", "bronco", "maverick",
                "lobo", "pickup", "pick-up", "camioneta", "f-150")) {
            return Intent.CATALOG;
        }
        return Intent.GENERAL;
    }

    private static boolean containsAny(String value, String... terms) {
        for (var term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
