package com.ford.service.ai.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves only short replies anchored to the latest explicit assistant offer. */
final class ConversationFollowUpResolver {
    private static final Pattern MODEL = Pattern.compile("(?iu)\\b(Mustang|Ranger|Bronco|Escape|Maverick|Explorer)\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern TRIM = Pattern.compile("(?iu)\\b(GT\\s+Convertible|GT|EcoBoost|Dark Horse)\\b");

    Optional<Resolution> resolve(String currentMessage, String trustedMemoryContext) {
        if (currentMessage == null || trustedMemoryContext == null) return Optional.empty();
        var reply = currentMessage.trim().toLowerCase(Locale.ROOT);
        if (reply.matches("(?:no|no gracias|ninguna|ninguno|mejor no)[.!]?")) return Optional.empty();
        var lines = trustedMemoryContext.lines().toList();
        String offer = null;
        int offerIndex = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            var line = lines.get(i).toLowerCase(Locale.ROOT);
            if (line.contains("asistente:") && line.contains("?")
                    && (line.contains("¿") || line.contains("prefiere") || line.contains("interesa")
                    || line.contains("desea") || line.contains("gustaría") || line.contains("comparamos"))) {
                offer = lines.get(i);
                offerIndex = i;
                break;
            }
        }
        if (offer == null || lines.subList(offerIndex + 1, lines.size()).stream()
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("usuario:"))) return Optional.empty();
        final var activeOffer = offer;
        var exchangeStart = -1;
        for (int i = offerIndex - 1; i >= 0; i--) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains("usuario:")) {
                exchangeStart = i;
                break;
            }
        }
        if (exchangeStart < 0) return Optional.empty();
        var exchange = String.join(" ", lines.subList(exchangeStart, offerIndex + 1));
        var models = findAll(MODEL, exchange);
        var years = findAll(YEAR, exchange);
        var antecedents = String.join(" ", lines.subList(exchangeStart, offerIndex));
        var trims = findAll(TRIM, antecedents);
        if (models.size() > 1 || years.size() > 1 || trims.size() > 1) return Optional.empty();

        String action;
        ConversationIntent intent = ConversationIntent.VEHICLE_DETAILS;
        ToolFamily family = ToolFamily.VEHICLE_DETAILS;
        if (reply.matches("(?:sí|si|sí,? me interesa|si,? me interesa|claro)[.!]?$")) {
            action = activeOffer.replaceFirst("(?iu)^.*?asistente:\\s*", "").trim();
        } else if (reply.matches("(?:manual|automática|automatica|estándar|estandar)[.!]?$")) {
            var choices = List.of("manual", "automática", "automatica", "estándar", "estandar").stream()
                    .filter(choice -> Pattern.compile("(?iu)\\b" + choice + "\\b").matcher(activeOffer).find())
                    .map(choice -> choice.startsWith("autom") ? "automática" : choice.startsWith("est") ? "estándar" : "manual")
                    .distinct().toList();
            if (choices.size() != 2 || choices.stream().noneMatch(choice -> choice.equals(reply.replaceAll("[.!]$", ""))))
                return Optional.empty();
            action = reply.replaceAll("[.!]$", "");
        } else if (reply.matches("(?:gt\\s+convertible|ecoboost|dark horse|gt)[.!]?$")) {
            var selected = reply.replaceAll("[.!]$", "").trim();
            if (!Pattern.compile("(?iu)\\b" + Pattern.quote(selected) + "\\b").matcher(activeOffer).find()
                    || models.size() != 1 || !models.getFirst().equalsIgnoreCase("Mustang") || years.size() != 1)
                return Optional.empty();
            action = selected;
            trims = List.of(selected.equalsIgnoreCase("gt convertible") ? "GT Convertible" : selected);
        } else return Optional.empty();

        var model = models.stream().findFirst();
        var year = years.stream().findFirst();
        var trim = trims.stream().findFirst();
        var entities = new VehicleEntityResolution(model, year.map(Integer::valueOf), trim, List.of(),
                model.isPresent() ? VehicleEntityResolution.Source.MEMORY_REFERENCE : VehicleEntityResolution.Source.NONE,
                year.isPresent() ? VehicleEntityResolution.Source.MEMORY_REFERENCE : VehicleEntityResolution.Source.NONE,
                trim.isPresent() ? VehicleEntityResolution.Source.MEMORY_REFERENCE : VehicleEntityResolution.Source.NONE,
                model.isPresent() || year.isPresent() || trim.isPresent()
                        ? VehicleEntityResolution.Status.RESOLVED : VehicleEntityResolution.Status.MISSING);
        return Optional.of(new Resolution(intent, family, entities, action));
    }

    private static List<String> findAll(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        var values = new java.util.LinkedHashSet<String>();
        while (matcher.find()) values.add(matcher.group(1).replaceAll("\\s+", " "));
        return List.copyOf(values);
    }

    record Resolution(ConversationIntent intent, ToolFamily toolFamily,
                      VehicleEntityResolution vehicleEntities, String pendingAction) {}
}
