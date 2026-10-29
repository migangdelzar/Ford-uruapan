package com.ford.service.ai.application;

import com.ford.service.catalog.api.VehicleModelNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves only explicit vehicle values and a unique model reference from trusted memory. */
public final class VehicleEntityResolver {
    private static final List<String> TRIMS = List.of(
            "King Ranch", "Outer Banks", "Dark Horse", "Badlands", "Wildtrak", "Platinum", "Lariat",
            "Tremor", "XLT", "Big Bend", "Heritage", "Raptor", "ST", "Active");
    private static final List<String> TRIM_QUALIFIERS = List.of(
            "Gasolina", "Diésel", "Diesel", "4x2", "4x4", "AWD", "FWD", "RWD",
            "Max", "Híbrida", "Hibrida", "Ultimate", "EcoBoost");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");
    private static final Pattern ANAPHORA = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:esa|ese|esa camioneta|ese modelo|esa version|esa versión|"
                    + "la misma|el mismo|esa misma|ese mismo|y esa|y ese|that one|that vehicle|it)(?![\\p{L}\\p{N}])");

    public VehicleEntityResolution resolve(String currentMessage, String trustedMemoryContext) {
        var current = currentMessage == null ? "" : currentMessage;
        var currentModels = findModels(current);
        var yearValues = years(current);
        var year = yearValues.size() == 1 ? Optional.of(yearValues.getFirst()) : Optional.<Integer>empty();
        var trim = findTrim(current);
        if (currentModels.size() > 1) {
            return result(Optional.empty(), year, trim, yearValues, VehicleEntityResolution.Source.NONE,
                    year.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                    trim.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                    VehicleEntityResolution.Status.AMBIGUOUS);
        }
        if (currentModels.size() == 1) {
            return result(Optional.of(currentModels.getFirst()), year, trim, yearValues,
                    VehicleEntityResolution.Source.CURRENT_MESSAGE,
                    year.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                    trim.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                    VehicleEntityResolution.Status.RESOLVED);
        }
        if (ANAPHORA.matcher(current).find()) {
            var memory = trustedMemoryContext == null ? "" : trustedMemoryContext;
            var reference = latestAssistantExchange(memory).orElse(memory);
            var memoryModels = findModels(reference);
            if (memoryModels.size() == 1) {
                var referencesSpecificVersion = Pattern.compile("(?iu)\\b(?:(?:esa|la misma)\\s+versi[oó]n)\\b")
                        .matcher(current).find();
                var memoryYears = referencesSpecificVersion ? years(reference) : List.<Integer>of();
                var memoryYear = memoryYears.size() == 1 ? Optional.of(memoryYears.getFirst()) : Optional.<Integer>empty();
                var memoryTrim = referencesSpecificVersion ? findUniqueTrim(reference) : Optional.<String>empty();
                return result(Optional.of(memoryModels.getFirst()), year.isPresent() ? year : memoryYear,
                        trim.isPresent() ? trim : memoryTrim, yearValues,
                        VehicleEntityResolution.Source.MEMORY_REFERENCE,
                        year.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE
                                : memoryYear.isPresent() ? VehicleEntityResolution.Source.MEMORY_REFERENCE
                                : VehicleEntityResolution.Source.NONE,
                        trim.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE
                                : memoryTrim.isPresent() ? VehicleEntityResolution.Source.MEMORY_REFERENCE
                                : VehicleEntityResolution.Source.NONE,
                        VehicleEntityResolution.Status.RESOLVED);
            }
            if (memoryModels.size() > 1) {
                return result(Optional.empty(), year, trim, yearValues, VehicleEntityResolution.Source.NONE,
                        year.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                        trim.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                        VehicleEntityResolution.Status.AMBIGUOUS);
            }
        }
        return result(Optional.empty(), year, trim, yearValues, VehicleEntityResolution.Source.NONE,
                year.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                trim.isPresent() ? VehicleEntityResolution.Source.CURRENT_MESSAGE : VehicleEntityResolution.Source.NONE,
                VehicleEntityResolution.Status.MISSING);
    }

    private static Optional<String> latestAssistantExchange(String memory) {
        var lines = memory.lines().map(String::strip).toList();
        var assistantIndex = -1;
        for (var index = 0; index < lines.size(); index++) {
            if (roleMessage(lines.get(index), "asistente:", "assistant:").isPresent()) assistantIndex = index;
        }
        if (assistantIndex < 0) return Optional.empty();
        var assistantMessage = roleMessage(lines.get(assistantIndex), "asistente:", "assistant:").orElseThrow();
        var userMessage = "";
        for (var index = assistantIndex - 1; index >= 0; index--) {
            var candidate = roleMessage(lines.get(index), "usuario:", "user:", "cliente:");
            if (candidate.isPresent()) {
                userMessage = candidate.get();
                break;
            }
        }
        return Optional.of(userMessage + " " + assistantMessage);
    }

    private static Optional<String> roleMessage(String line, String... roleLabels) {
        var normalized = line.toLowerCase(Locale.ROOT);
        for (var role : roleLabels) {
            var roleIndex = normalized.indexOf(role);
            if (roleIndex >= 0) return Optional.of(line.substring(roleIndex + role.length()).strip());
        }
        return Optional.empty();
    }

    private static VehicleEntityResolution result(Optional<String> model, Optional<Integer> year,
                                                   Optional<String> trim, List<Integer> comparisonYears,
                                                   VehicleEntityResolution.Source modelSource,
                                                   VehicleEntityResolution.Source yearSource,
                                                   VehicleEntityResolution.Source trimSource,
                                                   VehicleEntityResolution.Status status) {
        return new VehicleEntityResolution(model, year, trim, comparisonYears, modelSource, yearSource,
                trimSource, status);
    }

    private static List<String> findModels(String text) {
        if (text == null || text.isBlank()) return List.of();
        var normalized = text.toLowerCase(Locale.ROOT);
        var matches = new LinkedHashSet<String>();
        VehicleModelNames.all().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .filter(model -> Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                        + "(?![\\p{L}\\p{N}])").matcher(normalized).find())
                .filter(model -> matches.stream().noneMatch(existing -> existing.length() > model.length()
                        && existing.toLowerCase(Locale.ROOT).contains(model.toLowerCase(Locale.ROOT))))
                .forEach(matches::add);
        return List.copyOf(matches);
    }

    private static List<Integer> years(String text) {
        var values = new ArrayList<Integer>();
        var matcher = YEAR.matcher(text);
        while (matcher.find()) {
            var value = Integer.parseInt(matcher.group(1));
            if (value >= 2000 && value <= 2100 && !values.contains(value)) values.add(value);
        }
        return List.copyOf(values);
    }

    private static Optional<String> findTrim(String text) {
        var withoutModels = text;
        for (var model : findModels(text)) {
            withoutModels = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                    + "(?![\\p{L}\\p{N}])").matcher(withoutModels).replaceAll(" ");
        }
        var trimText = withoutModels;
        var baseTrim = TRIMS.stream()
                .filter(trim -> Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(trim)
                        + "(?![\\p{L}\\p{N}])").matcher(trimText).find())
                .findFirst();
        if (baseTrim.isEmpty()) return baseTrim;
        var qualifiers = String.join("|", TRIM_QUALIFIERS.stream().map(Pattern::quote).toList());
        var variant = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(" + Pattern.quote(baseTrim.get())
                + "(?:\\s+(?:" + qualifiers + ")){0,3})(?![\\p{L}\\p{N}])").matcher(trimText);
        if (variant.find()) {
            return Optional.of(variant.group(1).replaceAll("\\s+", " ").trim());
        }
        return baseTrim;
    }

    private static Optional<String> findUniqueTrim(String text) {
        var withoutModels = text;
        for (var model : findModels(text)) {
            withoutModels = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                    + "(?![\\p{L}\\p{N}])").matcher(withoutModels).replaceAll(" ");
        }
        var qualifiers = String.join("|", TRIM_QUALIFIERS.stream().map(Pattern::quote).toList());
        var variants = new java.util.LinkedHashMap<String, String>();
        for (var baseTrim : TRIMS) {
            var matcher = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(" + Pattern.quote(baseTrim)
                    + "(?:\\s+(?:" + qualifiers + ")){0,3})(?![\\p{L}\\p{N}])").matcher(withoutModels);
            while (matcher.find()) {
                var value = matcher.group(1).replaceAll("\\s+", " ").trim();
                variants.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            }
        }
        return variants.size() == 1 ? Optional.of(variants.values().iterator().next()) : Optional.empty();
    }
}
