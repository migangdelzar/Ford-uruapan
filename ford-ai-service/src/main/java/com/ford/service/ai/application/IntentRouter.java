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
        if (isComparison(normalized)) {
            return comparisonIntent(normalized);
        }
        if (containsAny(normalized, "disponible", "inventario", "stock", "unidades", "color")) {
            return Intent.INVENTORY;
        }
        if (containsAny(normalized, "mensualidad", "financiamiento", "financiar", "enganche")
                && containsAny(normalized, "busco", "recomienda", "opciones", "camioneta", " suv ",
                "vehículo", "vehiculo", "auto")) {
            return Intent.VEHICLE_FINANCE;
        }
        if (containsAny(normalized, "ford credit", "cat ", "tasa", "tasas", "plan de financiamiento",
                "planes de financiamiento", "plan de crédito", "plan de credito", "requisitos de crédito",
                "requisitos de credito")) {
            return Intent.FINANCE_PLANS;
        }
        if (containsAny(normalized, "mensualidad", "financiar", "financiamiento", "comisión", "comision",
                "credito", "crédito", "enganche", "cotización", "cotizacion")) {
            return Intent.FINANCE;
        }
        if (containsAny(normalized, "promoción", "promocion", "oferta", "bono")) {
            return Intent.OFFERS;
        }
        if (containsAny(normalized, "garantía", "garantia")) {
            return Intent.KNOWLEDGE;
        }
        if (containsAny(normalized, "especificación", "especificaciones", "especificacion", "motor", "ficha técnica")) {
            return Intent.CATALOG;
        }
        if (containsAny(normalized, "precio", "modelo", "version", "versión", "ranger", "bronco", "maverick")) {
            return Intent.CATALOG;
        }
        return Intent.GENERAL;
    }

    private static boolean isComparison(String text) {
        return containsAny(text, "compara", "comparar", "comparativo", "comparativa", "comparación",
                "comparacion", "diferencia", "diferencias", "que cambió entre", "qué cambió entre",
                "cambios entre", " vs ", " contra ", "versus");
    }

    private static Intent comparisonIntent(String text) {
        if (containsAny(text, "otra marca", "otras marcas", "competidor", "competencia", "rival",
                "chevrolet", "chevy", "toyota", "honda", "mazda", "nissan", "kia",
                "hyundai", "volkswagen", " vw ", "gmc", "jeep", "subaru", "ram ")) {
            return Intent.COMPETITOR_COMPARISON;
        }
        if (text.matches(".*\\b20\\d{2}\\b.*\\b20\\d{2}\\b.*")) {
            return Intent.YEAR_COMPARISON;
        }
        if (containsAny(text, "versión", "version", "trim", "ambiente", "trend", "titanium",
                "platinum", "active", "king ranch", "tremor", "wildtrak", "xlt", "limited")) {
            return Intent.TRIM_COMPARISON;
        }
        return Intent.MODEL_COMPARISON;
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
