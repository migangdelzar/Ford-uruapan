package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves only exact catalog identities explicitly named by the buyer in a replay. */
final class LocalBuyerVehicleResolver {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern BUYER_LINE = Pattern.compile("(?m)^\\s*- usuario:\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY = Pattern.compile("(?s)\\[HISTORIAL DE CONVERSACIÓN](.*?)\\[FIN DEL HISTORIAL]", Pattern.CASE_INSENSITIVE);

    Optional<VehicleRef> resolve(String currentMessage, String memoryContext, List<VehicleNameIndexEntry> entries) {
        var current = matches(currentMessage, entries);
        if (current.size() == 1) return Optional.of(current.getFirst());
        if (current.size() > 1) return Optional.empty();

        var history = memoryContext == null ? "" : memoryContext;
        var block = HISTORY.matcher(history);
        if (!block.find()) return Optional.empty();
        var lines = new ArrayList<String>();
        var buyer = BUYER_LINE.matcher(block.group(1));
        while (buyer.find()) lines.add(buyer.group(1));
        for (int i = lines.size() - 1; i >= 0; i--) {
            var matches = matches(lines.get(i), entries);
            if (!matches.isEmpty()) return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }
        return Optional.empty();
    }

    private List<VehicleRef> matches(String text, List<VehicleNameIndexEntry> entries) {
        if (text == null || text.isBlank()) return List.of();
        var normalized = normalize(text);
        var found = new ArrayList<VehicleRef>();
        for (var entry : entries) {
            var name = normalize(entry.model() + " " + entry.year() + (entry.trim() == null ? "" : " " + entry.trim()));
            int from = 0;
            while ((from = normalized.indexOf(name, from)) >= 0) {
                int to = from + name.length();
                if (boundaryBefore(normalized, from) && boundaryAfter(normalized, to)) {
                    var ref = new VehicleRef(entry.model().toUpperCase(Locale.ROOT), entry.year(),
                            entry.trim() == null ? null : normalize(entry.trim()));
                    if (!found.contains(ref)) found.add(ref);
                }
                from = to;
            }
        }
        return found;
    }

    private boolean boundaryBefore(String text, int index) {
        return index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
    }

    private boolean boundaryAfter(String text, int index) {
        return index == text.length() || !Character.isLetterOrDigit(text.charAt(index));
    }

    private String normalize(String value) {
        return SPACE.matcher(value.strip().toUpperCase(Locale.ROOT)).replaceAll(" ");
    }

    record VehicleRef(String model, int year, String trim) {}
}
