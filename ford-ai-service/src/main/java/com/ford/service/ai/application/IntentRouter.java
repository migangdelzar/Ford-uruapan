package com.ford.service.ai.application;

import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

public class IntentRouter {

    private static final Pattern COURTESY_ONLY_MESSAGE = Pattern.compile(
            "(?iu)^\\s*(?:(?:muchas|mil)\\s+)?gracias"
                    + "(?:\\s*,\\s*justo\\s+eso\\s+quer[ií]a\\s+explicar\\s+antes\\s+de\\s+ver\\s+modelos)?"
                    + "[.!]?\\s*$");

    private static final List<String> EXPLICIT_FACT_CUES = List.of(
            "precio", "cuesta", "costaría", "costaria", "modelo", "versión", "version", "año", "ano",
            "motor", "especificación", "especificacion", "rendimiento", "consumo", "potencia", "caballos",
            "dimensiones", "seguridad", "equipamiento", "tracción", "traccion", "comparar", "comparación",
            "comparacion", "diferencia", "versus", "promoción", "promocion", "oferta", "bono", "descuento",
            "disponible", "inventario", "stock", "color", "garantía", "garantia");

    private static final List<String> CONCRETE_FACT_CUES = List.of(
            "precio", "cuesta", "costaría", "costaria", "motor", "especificación", "especificacion",
            "rendimiento", "consumo", "potencia", "caballos", "dimensiones", "seguridad", "equipamiento",
            "tracción", "traccion", "comparar", "comparación", "comparacion", "diferencia", "versus",
            "promoción", "promocion", "oferta", "bono", "descuento", "disponible", "inventario", "stock",
            "garantía", "garantia");

    private static final List<Pattern> FORD_MODEL_PATTERNS = List.of(
            "bronco", "edge", "expedition", "explorer", "f-150", "f 150", "f150",
            "f-250", "f 250", "f250", "lobo", "maverick", "mustang",
            "police interceptor", "ranger", "super duty", "territory", "transit"
    ).stream().map(model -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
            + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList();
    private static final Pattern VERSION_CONFIRMATION = Pattern.compile(
            "(?iu)\\bcuando dices\\b.*\\b(?:sigues hablando de|te refieres a)\\b");

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
        if (isBuyerDiscoveryRequest(normalized)) {
            return Intent.GENERAL;
        }
        if (isPrePurchaseCriteriaQuestion(normalized)) {
            return Intent.GENERAL;
        }
         if (containsAny(normalized, "suv", "crossover", "camioneta", "vehículo", "vehiculo", "auto", "carro",
                 "pickup", "pick-up")
                && containsAny(normalized, "busco", "estoy buscando", "recomiend", "necesito", "quiero comprar",
                        "quiero ver", "para mi familia")) {
            return Intent.INVENTORY;
        }
        if (containsAny(normalized, "mensualidad", "financiamiento", "comisión", "credito", "crédito", "enganche")) {
            return Intent.FINANCE;
        }
        if (containsAny(normalized, "promoción", "promocion", "oferta", "bono")) {
            return Intent.OFFERS;
        }
        if (containsAny(normalized, "principales opciones", "opciones hay", "opciones para",
                "opciones de camionetas", "opciones de vehículos", "opciones de vehiculos",
                "modelos disponibles", "modelos hay", "modelos que hay",
                "modelos tienen", "camionetas hay", "camionetas tienen", "vehículos hay", "vehiculos hay")) {
            return Intent.INVENTORY;
        }
        if (containsAny(normalized, "me conviene", "me convendría", "me convendria", "me recomiendas",
                "me recomendarías", "me recomendarias", "se ajusta mejor a lo que necesito")
                && containsAny(normalized, "ford", "suv", "crossover", "camioneta", "vehículo", "vehiculo",
                        "pickup", "pick-up", "auto", "carro")) {
            return Intent.INVENTORY;
        }
        if (containsAny(normalized, "todavía no elijo", "todavia no elijo", "aún no elijo", "aun no elijo",
                "todavía no he elegido", "todavia no he elegido", "aún no he elegido", "aun no he elegido")
                && containsAny(normalized, "año", "ano", "versión", "version")
                && !containsAny(normalized, "opciones", "modelos", "recomiend", "precio", "cuesta",
                        "disponible", "comparar", "compara")) {
            return Intent.GENERAL;
        }
        if (mentionsFordModel(normalized) && containsAny(normalized,
                "especificación", "especificacion", "motor", "consumo", "kilómetro", "kilometro", "km/l",
                "rendimiento", "litro", "potencia", "caballos", "equipamiento", "seguridad", "tracción",
                "traccion", "dimensiones")) {
            return Intent.CATALOG;
        }
        if (containsAny(normalized, "garantía", "garantia", "especificación", "especificaciones", "motor",
                "consumo", "kilómetro", "kilometro", "km/l", "rendimiento", "litro", "potencia",
                "caballos", "equipamiento", "seguridad", "tracción", "traccion", "dimensiones")) {
            return Intent.KNOWLEDGE;
        }
        if (containsAny(normalized, "precio", "version", "versión", "ranger", "bronco", "maverick",
                "lobo", "raptor", "f-150")
                || mentionsFordModel(normalized)) {
            return Intent.CATALOG;
        }
        return Intent.GENERAL;
    }

    /** True only when deterministic routing identifies a source-backed Ford fact request. */
    public boolean isExplicitFordFactRequest(String text) {
        if (text == null || text.isBlank()) return false;
        var intent = route(text);
        if (intent != Intent.CATALOG && intent != Intent.INVENTORY && intent != Intent.OFFERS) {
            return false;
        }
        var normalized = text.toLowerCase(Locale.ROOT);
        return mentionsFordModel(normalized)
                && EXPLICIT_FACT_CUES.stream().anyMatch(normalized::contains);
    }

    /** Routes catalog-fact follow-ups over referenced Ford versions to catalog tools, not general knowledge. */
    public boolean isExplicitFordFactFollowUp(String text, String trustedMemoryContext) {
        if (text == null || text.isBlank() || trustedMemoryContext == null || trustedMemoryContext.isBlank()) return false;
        var normalized = text.toLowerCase(Locale.ROOT);
        var refersToPriorVehicle = containsAny(normalized, "esa version", "esa versión", "esas versiones", "esas dos versiones",
                "ambas versiones", "esas camionetas", "esas dos", "cada una");
        return refersToPriorVehicle && mentionsFordModel(trustedMemoryContext.toLowerCase(Locale.ROOT))
                && EXPLICIT_FACT_CUES.stream().anyMatch(normalized::contains);
    }

    /** Keeps questions confirming the assistant's immediately preceding vehicle reference conversational. */
    public boolean isAssistantVehicleReferenceConfirmation(String text, String trustedMemoryContext) {
        if (text == null || trustedMemoryContext == null || !VERSION_CONFIRMATION.matcher(text).find()) return false;
        var normalized = text.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, "versión", "version", "esa versión", "esa version")) return false;
        if (CONCRETE_FACT_CUES.stream().anyMatch(normalized::contains)) return false;
        var latestAssistantMessage = trustedMemoryContext.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains("asistente:"))
                .reduce((earlier, later) -> later)
                .orElse("")
                .toLowerCase(Locale.ROOT);
        return FORD_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find()
                && pattern.matcher(latestAssistantMessage).find());
    }

    /** True for a complete courtesy-only message, not a thanks-prefixed request. */
    public boolean isCourtesyOnly(String text) {
        return text != null && COURTESY_ONLY_MESSAGE.matcher(text).matches();
    }

    private static boolean mentionsFordModel(String normalized) {
        return FORD_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    public boolean isBuyerDiscoveryRequest(String normalized) {
        var asksForGuidance = containsAny(normalized, "me puedes orientar", "puedes orientarme",
                "podrías orientarme", "podrias orientarme", "necesito orientación", "necesito orientacion",
                "apenas estoy empezando", "apenas empiezo", "estoy empezando a buscar", "empezando a buscar");
        var mentionsVehicleCategory = containsAny(normalized, "suv", "crossover", "camioneta", "vehículo",
                "vehiculo", "auto", "carro", "pickup", "pick-up");
        var asksForSpecificProductInfo = mentionsFordModel(normalized)
                || containsAny(normalized, "opción", "opcion", "modelo", "versión", "version", "precio",
                        "cuesta", "motor", "especificación", "especificacion", "comparar", "promoción",
                        "promocion", "oferta", "bono", "descuento");
        return asksForGuidance && mentionsVehicleCategory && !asksForSpecificProductInfo;
    }

    public boolean isPrePurchaseCriteriaQuestion(String text) {
        var normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "dimensiones", "medidas")
                && containsAny(normalized, "revisar", "considerar", "tomar en cuenta")
                && containsAny(normalized, "antes de decidir", "para decidir", "antes de elegir");
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
