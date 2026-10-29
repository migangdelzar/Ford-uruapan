package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds one memory-only alternate from explicit Ford model/trim names already in this thread. */
public final class ConversationMemoryQueryExpander {

    private static final Pattern VEHICLE_TERM = Pattern.compile(
            "(?iu)\\b(?:Ford\\s+)?(?:Ranger|Bronco|Maverick|Escape|Explorer|Edge|Territory|Mustang|F-150|F-250|F-350)"
                    + "(?:\\s+(?:XL|XLT|Lariat|Limited|Platinum|Raptor|Badlands|Outer\\s+Banks|Big\\s+Bend|Sport|Base|Wildtrak|Tremor|King\\s+Ranch|Lobo))?\\b");
    private static final List<String> CONTEXT_REFERENCES = List.of(
            "la otra", "la otra version", "esa opcion", "ese modelo", "el mismo", "la misma",
            "those options", "the other one", "that version");

    public Optional<String> expand(String originalQuery, List<MemoryFact> activeFacts,
                                   List<ConversationMessage> recentMessages) {
        if (originalQuery == null || originalQuery.isBlank() || !isContextDependent(originalQuery)) {
            return Optional.empty();
        }
        var terms = new LinkedHashSet<String>();
        if (activeFacts != null) activeFacts.forEach(fact -> collectTerms(fact.content(), terms));
        if (recentMessages != null) recentMessages.forEach(message -> collectTerms(message.content(), terms));
        var queryKey = normalize(originalQuery);
        terms.removeIf(term -> queryKey.contains(normalize(term)));
        if (terms.isEmpty()) return Optional.empty();
        return Optional.of(originalQuery.trim() + " " + String.join(" ", terms));
    }

    public boolean isContextDependent(String query) {
        var normalized = normalize(query);
        return CONTEXT_REFERENCES.stream().anyMatch(normalized::contains);
    }

    private static void collectTerms(String text, LinkedHashSet<String> terms) {
        if (text == null || text.isBlank()) return;
        Matcher matcher = VEHICLE_TERM.matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }
}
