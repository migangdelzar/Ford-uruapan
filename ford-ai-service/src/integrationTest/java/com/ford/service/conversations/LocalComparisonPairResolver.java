package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

final class LocalComparisonPairResolver {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    Optional<Pair> resolve(String message, List<VehicleNameIndexEntry> entries) {
        if (message == null || message.isBlank() || entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        var normalizedMessage = normalize(message);
        var entriesByModelYear = new LinkedHashMap<ModelYear, List<VehicleNameIndexEntry>>();
        entries.stream()
                .filter(entry -> entry != null && entry.model() != null && !entry.model().isBlank()
                        && entry.trim() != null && !entry.trim().isBlank())
                .forEach(entry -> entriesByModelYear.computeIfAbsent(new ModelYear(entry.model(), entry.year()),
                        ignored -> new java.util.ArrayList<>()).add(entry));

        var resolvedPairs = new java.util.ArrayList<Pair>();
        for (var group : entriesByModelYear.entrySet()) {
            var anchor = normalize(group.getKey().model() + " " + group.getKey().year());
            if (!containsPhrase(normalizedMessage, anchor)) continue;
            var trims = group.getValue().stream().map(VehicleNameIndexEntry::trim)
                    .filter(trim -> containsPhrase(normalizedMessage, normalize(trim)))
                    .distinct().toList();
            if (trims.size() == 2) {
                resolvedPairs.add(new Pair(group.getKey().model(), group.getKey().year(), trims));
            }
        }
        return resolvedPairs.size() == 1 ? Optional.of(resolvedPairs.getFirst()) : Optional.empty();
    }

    Optional<Pair> resolveFollowUp(String currentMessage, String priorBuyerMessages,
                                   List<VehicleNameIndexEntry> entries) {
        var current = resolve(currentMessage, entries);
        if (current.isPresent() || currentMessage == null || !PAIR_FOLLOW_UP.matcher(normalize(currentMessage)).find()
                || priorBuyerMessages == null || priorBuyerMessages.isBlank()) {
            return current;
        }
        var messages = java.util.regex.Pattern.compile("(?m)^\\s*- usuario:\\s*(.*?)\\s*$")
                .matcher(priorBuyerMessages);
        var prior = new java.util.ArrayList<String>();
        while (messages.find()) prior.add(messages.group(1));
        for (var index = prior.size() - 1; index >= 0; index--) {
            var match = resolve(prior.get(index), entries);
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    private static final Pattern PAIR_FOLLOW_UP = Pattern.compile(
            "(?iu)\\b(?:esas dos|ambas|las dos|entre las dos|entre ambas|esas versiones|ambos|cada una|"
                    + "cada versi[oó]n|la comparaci[oó]n|comparaci[oó]n|distinguen|diferencias?)\\b");
    private static final Pattern COMPARISON_REQUEST = Pattern.compile(
            "(?iu)\\b(?:compar(?:a|ar|aci[oó]n)|diferencias?|comparativo|comparativa)\\b");

    boolean isComparisonIntent(String message) {
        if (message == null || message.isBlank()) return false;
        var normalized = normalize(message);
        return COMPARISON_REQUEST.matcher(normalized).find() || PAIR_FOLLOW_UP.matcher(normalized).find();
    }

    boolean isBuyerPairFollowUp(String message) {
        return message != null && !message.isBlank()
                && PAIR_FOLLOW_UP.matcher(normalize(message)).find();
    }

    private static boolean containsPhrase(String text, String phrase) {
        if (phrase.isBlank()) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static String normalize(String value) {
        return WHITESPACE.matcher(value.strip().toLowerCase(Locale.ROOT))
                .replaceAll(" ");
    }

    record Pair(String model, int year, List<String> trims) {
        Pair {
            trims = List.copyOf(trims);
        }
    }

    private record ModelYear(String model, int year) { }
}
