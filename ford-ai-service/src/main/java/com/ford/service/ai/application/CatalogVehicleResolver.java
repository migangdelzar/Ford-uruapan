package com.ford.service.ai.application;

import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Resolves extracted vehicle mentions against canonical names only; never returns vehicle facts. */
public final class CatalogVehicleResolver {
    public CatalogVehicleResolution resolve(VehicleEntityResolution mention, List<VehicleNameIndexEntry> entries) {
        Objects.requireNonNull(mention, "vehicle mention is required");
        if (mention.model().isEmpty() || mention.modelYear().isEmpty() || mention.trim().isEmpty()) {
            return CatalogVehicleResolution.notRequired();
        }
        return resolve(mention.model().orElseThrow(), mention.modelYear().orElseThrow(),
                mention.trim().orElseThrow(), entries);
    }

    public CatalogVehicleResolution resolve(String model, int year, String trim,
                                              List<VehicleNameIndexEntry> entries) {
        if (model == null || model.isBlank() || trim == null || trim.isBlank()) {
            return CatalogVehicleResolution.notRequired();
        }
        var safeEntries = entries == null ? List.<VehicleNameIndexEntry>of() : entries.stream()
                .filter(Objects::nonNull).toList();
        var requestedTrim = normalize(trim);
        var matchingModelYear = safeEntries.stream()
                .filter(entry -> entry.year() == year && normalize(entry.model()).equals(normalize(model)))
                .toList();
        if (matchingModelYear.isEmpty()) return CatalogVehicleResolution.missing();

        var exact = matchingModelYear.stream()
                .filter(entry -> entry.trim() != null && normalize(entry.trim()).equals(requestedTrim))
                .map(CatalogVehicleResolver::candidate)
                .distinct()
                .toList();
        if (exact.size() == 1) {
            return new CatalogVehicleResolution(CatalogVehicleResolution.Status.EXACT, exact.getFirst(), List.of());
        }
        if (exact.size() > 1) {
            return ambiguous(exact);
        }

        var partial = matchingModelYear.stream()
                .filter(entry -> entry.trim() != null
                        && startsWithWholeTrimTokens(normalize(entry.trim()), requestedTrim))
                .map(CatalogVehicleResolver::candidate)
                .distinct()
                .toList();
        if (partial.isEmpty()) return CatalogVehicleResolution.missing();
        if (partial.size() == 1) {
            return new CatalogVehicleResolution(CatalogVehicleResolution.Status.UNIQUE_CANONICAL_MATCH,
                    partial.getFirst(), List.of());
        }
        return ambiguous(partial);
    }

    private static CatalogVehicleResolution ambiguous(List<VehicleTrimCandidate> choices) {
        var ordered = choices.stream().sorted(Comparator.comparing(VehicleTrimCandidate::trim,
                String.CASE_INSENSITIVE_ORDER)).toList();
        return new CatalogVehicleResolution(CatalogVehicleResolution.Status.AMBIGUOUS, null, ordered);
    }

    private static VehicleTrimCandidate candidate(VehicleNameIndexEntry entry) {
        return new VehicleTrimCandidate(entry.model(), entry.year(), entry.trim());
    }

    private static boolean startsWithWholeTrimTokens(String canonicalTrim, String requestedTrim) {
        return canonicalTrim.startsWith(requestedTrim)
                && (canonicalTrim.length() == requestedTrim.length()
                || Character.isWhitespace(canonicalTrim.charAt(requestedTrim.length())));
    }

    private static String normalize(String value) {
        var decomposed = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return Pattern.compile("[^\\p{L}\\p{N}]+")
                .matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ").strip().replaceAll("\\s+", " ");
    }
}
