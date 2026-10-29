package com.ford.service.handoffcontact.application;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class AlternateContactRedactor {
    private static final String REDACTION = "[dato de contacto omitido]";

    public String redact(String text, Collection<String> knownValues) {
        if (text == null || text.isEmpty() || knownValues == null || knownValues.isEmpty()) return text;
        var values = knownValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        var redacted = text;
        for (var value : values) {
            redacted = Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(redacted).replaceAll(REDACTION);
        }
        return redacted;
    }

    String redactSpans(String text, List<Span> spans) {
        if (text == null || spans == null || spans.isEmpty()) return text;
        var ordered = spans.stream().sorted(Comparator.comparingInt(Span::start).reversed()).toList();
        var result = new StringBuilder(text);
        for (var span : ordered) result.replace(span.start(), span.end(), REDACTION);
        return result.toString();
    }

    record Span(int start, int end) { }
}
