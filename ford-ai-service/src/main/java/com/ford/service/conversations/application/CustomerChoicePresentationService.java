package com.ford.service.conversations.application;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.domain.ReplyOption;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presents only current catalog identities explicitly named by the final assistant response. */
@Component
public final class CustomerChoicePresentationService {
    private static final int MAX_OPTIONS = 3;
    private static final Pattern SELECTION_ID = Pattern.compile("\\((vehicle-[a-f0-9]{16})\\)\\s*$");

    public List<ReplyOption> fromAnswer(String answer, List<VehicleNameIndexEntry> currentEntries) {
        if (answer == null || answer.isBlank() || currentEntries == null || currentEntries.isEmpty()) return List.of();
        var normalizedAnswer = " " + normalize(answer) + " ";
        var matches = new ArrayList<MatchedEntry>();
        var seen = new LinkedHashMap<String, VehicleNameIndexEntry>();
        for (var entry : currentEntries) {
            if (!valid(entry)) continue;
            var label = label(entry);
            var normalizedLabel = normalize(label);
            if (normalizedLabel.isEmpty() || seen.putIfAbsent(identityKey(entry), entry) != null) continue;
            var index = normalizedAnswer.indexOf(" " + normalizedLabel + " ");
            if (index >= 0) matches.add(new MatchedEntry(index, entry, label));
        }
        return matches.stream()
                .sorted(Comparator.comparingInt(MatchedEntry::index))
                .limit(MAX_OPTIONS)
                .map(match -> new ReplyOption(id(match.entry()), match.label()))
                .toList();
    }

    public Optional<VehicleNameIndexEntry> resolveSelection(String inboundText,
                                                             List<VehicleNameIndexEntry> currentEntries) {
        if (inboundText == null || currentEntries == null || currentEntries.isEmpty()) return Optional.empty();
        Matcher matcher = SELECTION_ID.matcher(inboundText.strip());
        if (!matcher.find()) return Optional.empty();
        var id = matcher.group(1);
        var titleStart = inboundText.lastIndexOf('(', matcher.start());
        if (titleStart < 0) return Optional.empty();
        var visibleTitle = inboundText.substring(0, titleStart).strip();
        var marker = visibleTitle.lastIndexOf(':');
        if (marker >= 0) visibleTitle = visibleTitle.substring(marker + 1).strip();
        var normalizedTitle = normalize(visibleTitle);
        if (normalizedTitle.isEmpty()) return Optional.empty();

        return currentEntries.stream()
                .filter(CustomerChoicePresentationService::valid)
                .filter(entry -> id(entry).equals(id))
                .filter(entry -> normalize(label(entry)).equals(normalizedTitle))
                .findFirst();
    }

    private static boolean valid(VehicleNameIndexEntry entry) {
        return entry != null && entry.model() != null && !entry.model().isBlank() && entry.year() > 0
                && (entry.trim() == null || !entry.trim().isBlank());
    }

    private static String label(VehicleNameIndexEntry entry) {
        return entry.model() + " " + entry.year()
                + (entry.trim() == null ? "" : " " + entry.trim());
    }

    private static String identityKey(VehicleNameIndexEntry entry) {
        return normalize(entry.model()) + "|" + entry.year() + "|" + normalize(entry.trim());
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String id(VehicleNameIndexEntry entry) {
        try {
            var value = entry.model() + "|" + entry.year() + "|" + Objects.toString(entry.trim(), "");
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "vehicle-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record MatchedEntry(int index, VehicleNameIndexEntry entry, String label) { }
}
