package com.ford.service.handoffcontact.application;

import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AlternateContactCapturePolicy {
    private static final String POLICY_VERSION = "alternate-contact-consent-v1";
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.%+-])[a-z0-9._%+-]+@[a-z0-9.-]+[a-z0-9](?![\\w-])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+\\d[\\d .()\\-]{6,}\\d|(?:\\d[\\d .()\\-]{8,}\\d))(?!\\d)");
    private static final Pattern NEGATIVE_CONSENT = Pattern.compile(
            "\\b(?:no (?:(?:te|le|me|les) )?(?:autorizo|doy permiso)|no consiento|no compartir|no compartas|no quiero|prefiero no|sin autorizacion|no ford|no con ford|no al equipo de ford|no (?:[a-z0-9]+\\s+){0,3}(?:compart\\w+|autoriz\\w+|consient\\w+|permiso)|(?:revoc\\w+|retir\\w+|cancel\\w+|anul\\w+)\\s+(?:mi\\s+)?(?:autoriz\\w+|consent\\w+|permiso))\\b");
    private static final Pattern EXPLICIT_CONSENT = Pattern.compile(
            "\\b(autorizo|consiento|doy permiso)\\b.*\\b(compartir|comparta|compartan|compartirlo|compartirla)\\b");
    private static final Pattern RAW_EXPLICIT_CONSENT = Pattern.compile(
            "(?is)\\b(autorizo|consiento|doy permiso)\\b.*?\\b(compartir|comparta|compartan|compartirlo|compartirla)\\b");
    private static final Pattern CONTACT_MENTION = Pattern.compile(
            "\\b(numero|telefono|correo|email|contacto|dato)\\b");
    private static final Pattern PHONE_MENTION = Pattern.compile("(?iu)\\b(n[uú]mero|tel[eé]fono)\\b");
    private static final Pattern EMAIL_MENTION = Pattern.compile("(?iu)\\b(correo|e-?mail)\\b");
    private static final Pattern EXCLUSIVE_RECIPIENT = Pattern.compile(
            "(?iu)\\b(?:solo|solamente|únicamente|unicamente)\\s+(?:con|para)\\s+([\\p{L} ]{1,48})");
    private static final Pattern THIRD_PARTY_REFERENCE = Pattern.compile(
            "(?iu)\\b(?:herman[oa]|t[ií]?[oa]|espos[oa]|pareja|madre|padre|hij[oa]|amig[oa]|compa\u00f1er[oa]|jef[ea]|vecin[oa]|su)\\b");
    private static final Pattern FORD_RECIPIENT = Pattern.compile(
            "(?iu)\\b(?:con|para|a)\\s+(?:el\\s+equipo\\s+de\\s+)?ford\\b(?=\\s*(?:[:;,!?\\.]|\\[alternate contact\\]|$))");
    private static final Pattern BUYER_VALUE_GAP = Pattern.compile(
            "(?iu)^\\s*(?:(?:es|son)\\s*)?(?:(?:con|para|a)\\s+(?:el\\s+equipo\\s+de\\s+)?ford\\s*)?\\s*(?:[:,;]\\s*)?$");

    private final AlternateContactRedactor redactor;

    public AlternateContactCapturePolicy(AlternateContactRedactor redactor) {
        this.redactor = redactor;
    }

    public Extraction extract(String rawText, String consentMessageId, Instant receivedAt) {
        if (rawText == null || rawText.isEmpty()) return new Extraction(rawText == null ? "" : rawText, List.of());
        var candidates = findCandidates(rawText);
        var sanitized = redactor.redactSpans(rawText, candidates.stream()
                .map(candidate -> new AlternateContactRedactor.Span(candidate.start(), candidate.end())).toList());
        if (candidates.size() != 1 || consentMessageId == null || consentMessageId.isBlank() || receivedAt == null) {
            return new Extraction(sanitized, List.of());
        }

        var candidate = candidates.getFirst();
        var consentContext = candidateClause(rawText, candidate);
        var normalizedText = normalize(consentContext);
        var consentMatcher = RAW_EXPLICIT_CONSENT.matcher(consentContext);
        var bindingContext = consentContext.replace(candidate.rawValue(), "[alternate contact]");
        var hasBoundConsent = consentMatcher.find()
                && hasMatchingContactType(consentContext, candidate.type())
                && hasValueBoundFordConsent(bindingContext, candidate.type())
                && !THIRD_PARTY_REFERENCE.matcher(consentContext).find()
                && !exclusiveRecipientExcludesFord(consentContext);
        if (NEGATIVE_CONSENT.matcher(normalize(rawText)).find()
                || !EXPLICIT_CONSENT.matcher(normalizedText).find()
                || !CONTACT_MENTION.matcher(normalizedText).find()
                || !hasBoundConsent) {
            return new Extraction(sanitized, List.of());
        }
        var normalized = candidate.type() == ContactType.EMAIL
                ? normalizeEmail(candidate.rawValue()) : normalizePhone(candidate.rawValue());
        if (normalized.isEmpty()) return new Extraction(sanitized, List.of());
        return new Extraction(sanitized, List.of(new AlternateContactValue(candidate.type(), normalized.get(),
                receivedAt, consentMessageId, POLICY_VERSION)));
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        var spans = findCandidates(text).stream()
                .map(candidate -> new AlternateContactRedactor.Span(candidate.start(), candidate.end())).toList();
        return redactor.redactSpans(text, spans);
    }

    private static String candidateClause(String text, Candidate candidate) {
        var beforeStart = Math.max(0, candidate.start() - 160);
        var afterEnd = Math.min(text.length(), candidate.end() + 160);
        var before = text.substring(beforeStart, candidate.start());
        var after = text.substring(candidate.end(), afterEnd);
        var leftBoundary = lastClauseBoundary(before);
        var rightBoundary = firstClauseBoundary(after);
        return before.substring(leftBoundary + 1) + "[alternate contact]" + after.substring(0, rightBoundary);
    }

    private static int lastClauseBoundary(String text) {
        for (var index = text.length() - 1; index >= 0; index--) {
            if (isClauseBoundary(text.charAt(index))) return index;
        }
        return -1;
    }

    private static int firstClauseBoundary(String text) {
        for (var index = 0; index < text.length(); index++) {
            if (isClauseBoundary(text.charAt(index))) return index;
        }
        return text.length();
    }

    private static boolean isClauseBoundary(char value) {
        return value == '.' || value == '!' || value == '?' || value == ';' || value == '\n' || value == '\r';
    }

    private static List<Candidate> findCandidates(String text) {
        var candidates = new ArrayList<Candidate>();
        addMatches(candidates, EMAIL.matcher(text), ContactType.EMAIL);
        addMatches(candidates, PHONE.matcher(text), ContactType.PHONE);
        candidates.sort(java.util.Comparator.comparingInt(Candidate::start));
        var nonOverlapping = new ArrayList<Candidate>();
        for (var candidate : candidates) {
            if (nonOverlapping.stream().noneMatch(existing -> candidate.start() < existing.end()
                    && existing.start() < candidate.end())) nonOverlapping.add(candidate);
        }
        return List.copyOf(nonOverlapping);
    }

    private static void addMatches(List<Candidate> candidates, Matcher matcher, ContactType type) {
        while (matcher.find()) candidates.add(new Candidate(type, matcher.group(), matcher.start(), matcher.end()));
    }

    private static boolean hasMatchingContactType(String context, ContactType expected) {
        var phoneMentions = countMentions(PHONE_MENTION.matcher(context));
        var emailMentions = countMentions(EMAIL_MENTION.matcher(context));
        if (phoneMentions + emailMentions != 1) return false;
        return expected == ContactType.PHONE ? phoneMentions == 1 : emailMentions == 1;
    }

    private static boolean exclusiveRecipientExcludesFord(String context) {
        var matcher = EXCLUSIVE_RECIPIENT.matcher(context);
        while (matcher.find()) {
            if (!normalize(matcher.group(1)).contains("ford")) return true;
        }
        return false;
    }

    private static boolean hasValueBoundFordConsent(String context, ContactType type) {
        var contactType = type == ContactType.PHONE ? "(?:n[uú]mero|tel[eé]fono)" : "(?:correo|e-?mail)";
        var marker = "[alternate contact]";
        var markerStart = context.indexOf(marker);
        if (markerStart < 0) return false;
        var markerEnd = markerStart + marker.length();
        var ownerType = Pattern.compile("(?iu)\\b(?:mi|m[ií]o|m[ií]a|este|esta)\\s+" + contactType + "\\b");
        var consent = Pattern.compile("(?iu)\\b(?:autorizo|consiento|doy permiso)\\b");
        var share = Pattern.compile("(?iu)\\b(?:compartir|comparta|compartan|compartirlo|compartirla)\\b");

        var consentMatcher = consent.matcher(context);
        while (consentMatcher.find()) {
            var shareMatcher = share.matcher(context);
            if (!shareMatcher.find(consentMatcher.end())) continue;
            var ownerMatcher = ownerType.matcher(context);
            if (!ownerMatcher.find(shareMatcher.end())) continue;

            if (ownerMatcher.start() < markerStart && markerStart - ownerMatcher.end() <= 100
                    && isUnambiguousBuyerValue(context, ownerMatcher.end(), markerStart)) {
                var recipientMatcher = FORD_RECIPIENT.matcher(context);
                while (recipientMatcher.find(ownerMatcher.end())) {
                    if (recipientMatcher.start() - ownerMatcher.end() > 64) break;
                    if (recipientMatcher.start() <= markerEnd + 32 && markerStart <= recipientMatcher.end() + 32)
                        return true;
                }
            }
        }

        var ownerMatcher = ownerType.matcher(context);
        while (ownerMatcher.find()) {
            if (ownerMatcher.end() > markerStart || markerStart - ownerMatcher.end() > 64
                    || !isUnambiguousBuyerValue(context, ownerMatcher.end(), markerStart)) continue;
            var consentMatcherAfterValue = consent.matcher(context);
            if (!consentMatcherAfterValue.find(markerEnd)) continue;
            var shareMatcherAfterValue = share.matcher(context);
            if (!shareMatcherAfterValue.find(consentMatcherAfterValue.end())) continue;
            var recipientMatcher = FORD_RECIPIENT.matcher(context);
            if (recipientMatcher.find(shareMatcherAfterValue.end())
                    && recipientMatcher.start() - shareMatcherAfterValue.end() <= 40) return true;
        }
        return false;
    }

    private static boolean isUnambiguousBuyerValue(String text, int ownerEnd, int candidateStart) {
        if (ownerEnd > candidateStart) return false;
        return BUYER_VALUE_GAP.matcher(text.substring(ownerEnd, candidateStart)).matches();
    }

    private static int countMentions(Matcher matcher) {
        var count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static Optional<String> normalizeEmail(String value) {
        var trimmed = value.strip().replaceFirst("[.,;:!?]+$", "");
        if (trimmed.length() > 254 || trimmed.indexOf('@') < 1) return Optional.empty();
        var at = trimmed.lastIndexOf('@');
        var local = trimmed.substring(0, at);
        var domain = trimmed.substring(at + 1);
        if (local.length() > 64 || domain.length() > 253 || local.startsWith(".") || local.endsWith(".")
                || local.contains("..") || domain.startsWith(".") || domain.endsWith("."))
            return Optional.empty();
        var labels = domain.split("\\.", -1);
        if (labels.length < 2 || labels[labels.length - 1].length() < 2
                || !labels[labels.length - 1].matches("[A-Za-z]{2,}")) return Optional.empty();
        for (var label : labels) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                return Optional.empty();
        }
        return Optional.of(local + "@" + domain.toLowerCase(Locale.ROOT));
    }

    private static Optional<String> normalizePhone(String value) {
        var trimmed = value.strip();
        var digits = trimmed.replaceAll("\\D", "");
        if (trimmed.startsWith("+")) {
            if (digits.length() < 8 || digits.length() > 15 || digits.charAt(0) == '0') return Optional.empty();
            return Optional.of("+" + digits);
        }
        if (digits.length() == 10 && digits.charAt(0) != '0') return Optional.of("+52" + digits);
        return Optional.empty();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip().replaceAll("\\s+", " ");
    }

    private record Candidate(ContactType type, String rawValue, int start, int end) { }

    public record Extraction(String sanitizedText, List<AlternateContactValue> acceptedValues) {
        public Extraction {
            acceptedValues = List.copyOf(acceptedValues == null ? List.of() : acceptedValues);
        }
    }
}
