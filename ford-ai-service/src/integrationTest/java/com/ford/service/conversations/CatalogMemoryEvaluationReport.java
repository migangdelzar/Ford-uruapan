package com.ford.service.conversations;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Scores reviewed assertions and compares baseline/candidate outcomes without persisting answer text. */
final class CatalogMemoryEvaluationReport {
    private CatalogMemoryEvaluationReport() { }

    static CaseResult evaluate(CatalogMemoryEvaluationScenario.Case testCase, String answer,
                               Set<CatalogMemoryEvaluationScenario.EvidenceKind> evidenceKinds,
                               boolean memoryAssertionsPass) {
        Objects.requireNonNull(testCase, "evaluation case is required");
        var normalizedAnswer = normalize(answer);
        var evidence = evidenceKinds == null ? Set.<CatalogMemoryEvaluationScenario.EvidenceKind>of()
                : Set.copyOf(evidenceKinds);
        var reasons = new ArrayList<String>();
        var missingClaim = testCase.requiredClaims().stream()
                .map(CatalogMemoryEvaluationReport::normalize)
                .anyMatch(claim -> !normalizedAnswer.contains(claim));
        if (missingClaim) reasons.add("REQUIRED_CLAIM_MISSING");
        var forbiddenClaim = testCase.forbiddenClaims().stream()
                .map(CatalogMemoryEvaluationReport::normalize)
                .anyMatch(normalizedAnswer::contains);
        if (forbiddenClaim) reasons.add("FORBIDDEN_CLAIM_PRESENT");
        var requiredToolEvidence = testCase.requiredEvidenceKinds().stream()
                .filter(kind -> kind != CatalogMemoryEvaluationScenario.EvidenceKind.SOURCE_CITATION)
                .toList();
        var missingEvidence = !evidence.containsAll(requiredToolEvidence);
        if (testCase.requiredEvidenceKinds().contains(CatalogMemoryEvaluationScenario.EvidenceKind.SOURCE_CITATION)
                && !hasVisibleCitation(answer)) {
            missingEvidence = true;
        }
        if (missingEvidence) reasons.add("REQUIRED_EVIDENCE_MISSING");
        var outcomeMismatch = testCase.expectedOutcome() == CatalogMemoryEvaluationScenario.ExpectedOutcome.ANSWER
                ? answer == null || answer.isBlank() || isClarification(normalizedAnswer)
                : !isClarification(normalizedAnswer);
        if (outcomeMismatch) reasons.add("EXPECTED_OUTCOME_MISMATCH");
        if (!memoryAssertionsPass) reasons.add("MEMORY_ASSERTION_FAILED");
        var correct = reasons.isEmpty();
        var criticalError = testCase.criticality() == CatalogMemoryEvaluationScenario.Criticality.CRITICAL
                && (forbiddenClaim || missingEvidence
                || (missingClaim && testCase.baselineStatus()
                        != CatalogMemoryEvaluationScenario.BaselineStatus.KNOWN_GAP));
        return new CaseResult(testCase.id(), testCase.category(), testCase.baselineStatus(),
                testCase.candidateIntent(), testCase.criticality(), correct, criticalError, List.copyOf(reasons));
    }

    static Set<CatalogMemoryEvaluationScenario.EvidenceKind> evidenceKinds(
            List<LocalQwenTranscriptWriter.ToolTrace> traces) {
        var result = EnumSet.noneOf(CatalogMemoryEvaluationScenario.EvidenceKind.class);
        for (var trace : traces == null ? List.<LocalQwenTranscriptWriter.ToolTrace>of() : traces) {
            if (!"returned".equals(trace.outcome()) || trace.evidenceCount() == null || trace.evidenceCount() < 1) {
                continue;
            }
            switch (trace.toolName()) {
                case "search_vehicles", "get_vehicle" -> result.add(
                        CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE);
                case "search_vehicle_and_offers" -> {
                    result.add(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE);
                    result.add(CatalogMemoryEvaluationScenario.EvidenceKind.STRUCTURED_PRICE);
                    result.add(CatalogMemoryEvaluationScenario.EvidenceKind.STRUCTURED_PROMOTION);
                }
                case "search_offers", "list_promotions" -> result.add(
                        CatalogMemoryEvaluationScenario.EvidenceKind.STRUCTURED_PROMOTION);
                case "compare_vehicles", "compare_trims", "compare_model_years", "get_variant_comparisons" ->
                        result.add(CatalogMemoryEvaluationScenario.EvidenceKind.COMPETITOR_COMPARISON);
                case "get_vehicle_provenance", "get_page_links" -> { }
                default -> { }
            }
            if ("get_vehicle".equals(trace.toolName())) {
                result.add(CatalogMemoryEvaluationScenario.EvidenceKind.STRUCTURED_PRICE);
            }
        }
        return Set.copyOf(result);
    }

    static PairedResult compare(List<CatalogMemoryEvaluationScenario.Case> cases,
                                List<CaseResult> baseline, List<CaseResult> candidate) {
        var baselineById = index(baseline);
        var candidateById = index(candidate);
        var regressions = new ArrayList<String>();
        var unfixedGaps = new ArrayList<String>();
        var criticalFailures = new HashSet<String>();
        var missing = new ArrayList<String>();
        for (var testCase : cases) {
            var baselineResult = baselineById.get(testCase.id());
            var candidateResult = candidateById.get(testCase.id());
            if (baselineResult == null || candidateResult == null) {
                missing.add(testCase.id());
                continue;
            }
            if (testCase.baselineStatus() == CatalogMemoryEvaluationScenario.BaselineStatus.EXPECTED_CORRECT
                    && baselineResult.correct() && !candidateResult.correct()) {
                regressions.add(testCase.id());
            }
            if (testCase.candidateIntent() == CatalogMemoryEvaluationScenario.CandidateIntent.GAP_FIX
                    && !candidateResult.correct()) {
                unfixedGaps.add(testCase.id());
            }
            if (baselineResult.criticalError()) criticalFailures.add("baseline:" + testCase.id());
            if (candidateResult.criticalError()) criticalFailures.add("candidate:" + testCase.id());
        }
        var passed = regressions.isEmpty() && unfixedGaps.isEmpty()
                && criticalFailures.isEmpty() && missing.isEmpty();
        return new PairedResult(passed, List.copyOf(regressions), List.copyOf(unfixedGaps),
                criticalFailures.stream().sorted().toList(), List.copyOf(missing));
    }

    private static Map<String, CaseResult> index(List<CaseResult> results) {
        var indexed = new HashMap<String, CaseResult>();
        for (var result : results == null ? List.<CaseResult>of() : results) {
            if (indexed.putIfAbsent(result.caseId(), result) != null) {
                throw new IllegalArgumentException("duplicate evaluation result case ID");
            }
        }
        return indexed;
    }

    private static boolean isClarification(String normalizedAnswer) {
        return normalizedAnswer.startsWith("?") || normalizedAnswer.contains("no puedo confirmar")
                || normalizedAnswer.contains("no tengo datos") || normalizedAnswer.contains("no encuentro")
                || normalizedAnswer.contains("no cuento con") || normalizedAnswer.contains("para precisar")
                || normalizedAnswer.contains("no confirma disponibilidad exacta")
                || normalizedAnswer.contains("me comparte") || normalizedAnswer.contains("que ano")
                || normalizedAnswer.contains("que version");
    }

    private static boolean hasVisibleCitation(String answer) {
        return answer != null && answer.matches("(?is).*\\b(?:https?://|www\\.)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}?]+", " ").trim();
    }

    record CaseResult(String caseId, CatalogMemoryEvaluationScenario.Category category,
                      CatalogMemoryEvaluationScenario.BaselineStatus baselineStatus,
                      CatalogMemoryEvaluationScenario.CandidateIntent candidateIntent,
                      CatalogMemoryEvaluationScenario.Criticality criticality,
                      boolean correct, boolean criticalError, List<String> reasonCodes) {
        CaseResult {
            Objects.requireNonNull(caseId);
            Objects.requireNonNull(category);
            Objects.requireNonNull(baselineStatus);
            Objects.requireNonNull(candidateIntent);
            Objects.requireNonNull(criticality);
            reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
        }
    }

    record PairedResult(boolean passed, List<String> regressions, List<String> unfixedGaps,
                        List<String> criticalFailures, List<String> missingCaseIds) {
        PairedResult {
            regressions = List.copyOf(regressions);
            unfixedGaps = List.copyOf(unfixedGaps);
            criticalFailures = List.copyOf(criticalFailures);
            missingCaseIds = List.copyOf(missingCaseIds);
        }
    }
}
