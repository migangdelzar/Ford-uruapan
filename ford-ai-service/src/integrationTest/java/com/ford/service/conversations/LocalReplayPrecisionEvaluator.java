package com.ford.service.conversations;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic diagnostics for catalog claims and buyer-memory recall in local synthetic replay. */
final class LocalReplayPrecisionEvaluator {
    private static final Pattern URL = Pattern.compile("(?iu)https?://\\S+");
    private static final Pattern MODEL_IDENTITY = Pattern.compile(
            "(?iu)\\b(?:Ford\\s+)?(?:Ranger|Bronco|Maverick|Escape|Explorer|Expedition|"
                    + "F\\s?[- ]?150|F\\s?[- ]?250|Transit(?:\\s+Custom)?|E\\s+Transit)\\s+20\\d{2}"
                    + "(?:\\s+(?:XLT|XL|Lariat|Wildtrak|Limited|Platinum|Raptor|GASOLINA|DI[EÉ]SEL|"
                    + "H[IÍ]BRIDA|4X2|4X4|4X4|AWD|4WD|V6|V8|2\\.0L|3\\.0L)){0,7}");
    private static final Pattern CURRENCY = Pattern.compile(
            "(?iu)(?:\\$\\s*\\d[\\d,.]*\\s*(?:MXN)?|\\b\\d[\\d,.]*\\s*MXN\\b)");
    private static final Pattern VALUE_WITH_UNIT = Pattern.compile(
            "(?iu)\\b\\d+(?:[.,]\\d+)?\\s*(?:hp|cv|kw|kg|mm|cm|m|l|lt|km/l|km\\s*/\\s*l|nm|lb-ft)\\b");
    private static final Pattern DATE = Pattern.compile(
            "(?iu)\\b(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+de\\s+"
                    + "(?:enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
                    + "(?:\\s+de\\s+\\d{4})?)\\b");
    private static final Pattern TORQUE_CLAIM = Pattern.compile("(?iu)\\b(?:torque|par motor)\\b[^.!?\\n]*");
    private static final Map<String, List<Pattern>> MEMORY_PATTERNS = Map.of(
            "weekday_work", List.of(Pattern.compile("(?iu)\\b(?:trabaj\\w*|empleo)\\b")),
            "weekend_family", List.of(Pattern.compile("(?iu)\\bfamilia\\b")),
            "bicycles", List.of(Pattern.compile("(?iu)\\b(?:bicicletas?|bicis)\\b")),
            "camping_gear", List.of(Pattern.compile("(?iu)\\b(?:campamento|camping)\\b")),
            "ranger_2027_xlt_gasolina_4x2", List.of(Pattern.compile(
                    "(?iu)\\branger\\b(?s:.*)\\b2027\\b(?s:.*)\\bxlt\\b(?s:.*)"
                            + "\\bgasolina\\b(?s:.*)\\b4\\s*x\\s*2\\b")));
    private static final Pattern WEEKDAY_CONTRADICTION = Pattern.compile(
            "(?iu)(?:\\bentre\\s+semana\\b(?s:.*)\\b(?:escuela|estudio|estudiar|amigos|vacaciones)\\b"
                    + "|\\b(?:escuela|estudio|estudiar|amigos|vacaciones)\\b(?s:.*)\\bentre\\s+semana\\b)");
    private static final Pattern WEEKEND_CONTRADICTION = Pattern.compile(
            "(?iu)(?:\\b(?:fines?\\s+de\\s+semana|fin\\s+de\\s+semana)\\b(?s:.*)"
                    + "\\b(?:amigos|pareja|solo|sola)\\b|\\b(?:amigos|pareja|solo|sola)\\b(?s:.*)"
                    + "\\b(?:fines?\\s+de\\s+semana|fin\\s+de\\s+semana)\\b)");

    PrecisionResult evaluateCatalog(NaturalBuyerConversationScenario.Turn turn, String answer,
                                    List<String> sameTurnEvidenceTexts) {
        Objects.requireNonNull(turn, "turn is required");
        var evidence = safeTexts(sameTurnEvidenceTexts).stream().map(LocalReplayPrecisionEvaluator::normalize)
                .toList();
        var claims = new LinkedHashSet<String>();
        addMatches(claims, MODEL_IDENTITY, answer);
        addMatches(claims, CURRENCY, answer);
        addMatches(claims, VALUE_WITH_UNIT, answer);
        addMatches(claims, DATE, answer);
        addMatches(claims, URL, answer);
        addMatches(claims, TORQUE_CLAIM, answer);

        var supported = new ArrayList<String>();
        var unsupported = new ArrayList<String>();
        var unscored = new ArrayList<String>();
        if (claims.isEmpty() && answer != null && !answer.isBlank()
                && (turn.expectation() == NaturalBuyerConversationScenario.Expectation.CATALOG_FACT
                || turn.expectation() == NaturalBuyerConversationScenario.Expectation.OFFER)) {
            unscored.add(answer.strip());
        }
        for (var claim : claims) {
            if (claim.toLowerCase(Locale.ROOT).startsWith("torque") || claim.toLowerCase(Locale.ROOT).startsWith("par motor")) {
                unscored.add(claim);
            } else if (evidence.stream().anyMatch(text -> containsExactClaim(text, normalize(claim)))) {
                supported.add(claim);
            } else {
                unsupported.add(claim);
            }
        }
        return new PrecisionResult(supported, unsupported, unscored, List.of());
    }

    PrecisionResult evaluateMemory(NaturalBuyerConversationScenario.Turn turn, String answer,
                                   List<String> priorBuyerMessages) {
        Objects.requireNonNull(turn, "turn is required");
        var prior = String.join("\n", safeTexts(priorBuyerMessages));
        var answerText = answer == null ? "" : answer;
        var supported = new ArrayList<String>();
        var unsupported = new ArrayList<String>();
        var missed = new ArrayList<String>();

        var factOrder = new LinkedHashSet<>(turn.expectedMemoryFacts());
        factOrder.addAll(MEMORY_PATTERNS.keySet());
        for (var fact : factOrder) {
            var patterns = MEMORY_PATTERNS.getOrDefault(fact, List.of());
            var answerClaimsFact = patterns.stream().anyMatch(pattern -> pattern.matcher(answerText).find());
            var historySupportsFact = patterns.stream().anyMatch(pattern -> pattern.matcher(prior).find());
            if (!answerClaimsFact) continue;
            if (historySupportsFact) supported.add(fact);
            else unsupported.add(fact);
        }
        for (var fact : turn.expectedMemoryFacts()) {
            var answerClaimsFact = MEMORY_PATTERNS.getOrDefault(fact, List.of()).stream()
                    .anyMatch(pattern -> pattern.matcher(answerText).find());
            var contradiction = switch (fact) {
                case "weekday_work" -> WEEKDAY_CONTRADICTION.matcher(answerText).find();
                case "weekend_family" -> WEEKEND_CONTRADICTION.matcher(answerText).find();
                default -> false;
            };
            if (contradiction && !unsupported.contains(fact)) unsupported.add(fact);
            else if (!answerClaimsFact && !contradiction) missed.add(fact);
        }
        return new PrecisionResult(supported, unsupported, List.of(), missed);
    }

    private static void addMatches(Set<String> target, Pattern pattern, String input) {
        if (input == null || input.isBlank()) return;
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) target.add(matcher.group().strip().replaceAll("[.,;:!?)}]+$", ""));
    }

    private static List<String> safeTexts(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("(?<=\\d)[,.](?=\\d{3}(?:\\D|$))", "")
                .replaceAll("[^\\p{L}\\p{N}/.:]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean containsExactClaim(String evidence, String claim) {
        if (claim.isBlank()) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(claim) + "(?![\\p{L}\\p{N}])")
                .matcher(evidence).find();
    }

    record PrecisionResult(List<String> supportedClaims, List<String> unsupportedClaims,
                           List<String> unscoredClaims, List<String> recallMisses) {
        PrecisionResult {
            supportedClaims = List.copyOf(supportedClaims == null ? List.of() : supportedClaims);
            unsupportedClaims = List.copyOf(unsupportedClaims == null ? List.of() : unsupportedClaims);
            unscoredClaims = List.copyOf(unscoredClaims == null ? List.of() : unscoredClaims);
            recallMisses = List.copyOf(recallMisses == null ? List.of() : recallMisses);
        }

        int scoredCount() { return supportedClaims.size() + unsupportedClaims.size(); }
        int totalCandidateCount() { return scoredCount() + unscoredClaims.size(); }
        String precision() {
            var denominator = scoredCount();
            return denominator == 0 ? "N/A" : percent(supportedClaims.size(), denominator);
        }
        String coverage() {
            var denominator = totalCandidateCount();
            return denominator == 0 ? "N/A" : percent(scoredCount(), denominator);
        }
        private static String percent(int numerator, int denominator) {
            return Math.round(100.0 * numerator / denominator) + "%";
        }
    }
}
