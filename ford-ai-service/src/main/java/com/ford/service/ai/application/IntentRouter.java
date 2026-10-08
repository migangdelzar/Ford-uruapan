package com.ford.service.ai.application;

import java.util.Locale;

public class IntentRouter {

    public Intent route(String text) {
        if (text == null || text.isBlank()) {
            return Intent.GENERAL;
        }
        var normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "receta médica", "receta medica", "diagnóstico médico", "diagnostico medico",
                "resultado del partido", "pronóstico del tiempo", "pronostico del tiempo", "escribe un poema",
                "escribe una canción", "escribe una cancion", "código para", "codigo para")) {
            return Intent.OUT_OF_SCOPE;
        }
        if (containsAny(normalized, "hablar con un asesor", "hablar con una asesora", "hablar con una persona",
                "hablar con alguien", "quiero un asesor", "quiero una asesora", "humano", "que me llame",
                "que me llamen")) {
            return Intent.LEAD_HANDOFF;
        }
        if (containsAny(normalized, "prueba de manejo", "agendar una cita", "agendar cita", "hacer una cita",
                "reservar una cita", "quiero una cita", "programar una cita", "agendar")) {
            return Intent.APPOINTMENT;
        }
        if (containsAny(normalized, "dar mi auto a cuenta", "dar mi carro a cuenta", "tomo mi auto",
                "reciben mi auto", "reciben mi carro", "trade-in", "trade in", "valuar mi auto",
                "valuar mi carro", "valor de mi auto", "valor de mi carro", "toma de auto")) {
            return Intent.TRADE_IN;
        }
        if (containsAny(normalized, "queja", "reclamo", "reclamación", "reclamacion", "estatus de",
                "seguimiento de", "estado de mi")) {
            return Intent.COMPLAINT_STATUS;
        }
        if (containsAny(normalized, "servicio", "taller", "mantenimiento", "refacciones", "repuestos")) {
            return Intent.SERVICE_SUPPORT;
        }
        if (containsAny(normalized, "horario", "ubicación", "ubicacion", "dirección de la agencia",
                "direccion de la agencia", "teléfono de la agencia", "telefono de la agencia",
                "dónde están", "donde estan", "sucursal", "agencia")) {
            return Intent.DEALERSHIP_INFO;
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
        if (containsAny(normalized, "garantía", "garantia", "especificación", "especificaciones", "motor",
                "consumo", "kilómetro", "kilometro", "km/l", "rendimiento", "litro", "potencia",
                "caballos", "equipamiento", "seguridad", "tracción", "traccion", "dimensiones")) {
            return Intent.KNOWLEDGE;
        }
        if (containsAny(normalized, "precio", "modelo", "version", "versión", "ranger", "bronco", "maverick",
                "lobo", "raptor", "pickup", "pick-up", "camioneta", "f-150")) {
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
