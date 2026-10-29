package com.ford.service.conversations;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Reviewed synthetic catalog and conversation-memory cases for paired local Qwen runs. */
final class CatalogMemoryEvaluationScenario {
    private static final String RESOURCE = "fixtures/meta/catalog-memory-evaluation.json";
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final TypeReference<List<Case>> CASES_TYPE = new TypeReference<>() { };

    private CatalogMemoryEvaluationScenario() { }

    static List<Case> load(ObjectMapper objectMapper) throws IOException {
        Objects.requireNonNull(objectMapper, "object mapper is required");
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return validateCases(objectMapper.readValue(input, CASES_TYPE));
        }
    }

    static List<Case> validateCases(List<Case> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation cases are required");
        }
        var ids = new HashSet<String>();
        for (var testCase : cases) {
            Objects.requireNonNull(testCase, "evaluation case is required");
            if (testCase.id() == null || !SAFE_ID.matcher(testCase.id()).matches()) {
                throw new IllegalArgumentException("evaluation case ID is invalid");
            }
            if (!ids.add(testCase.id())) {
                throw new IllegalArgumentException("duplicate evaluation case ID");
            }
            Objects.requireNonNull(testCase.category(), "evaluation case category is required");
            Objects.requireNonNull(testCase.baselineStatus(), "baseline status is required");
            Objects.requireNonNull(testCase.candidateIntent(), "candidate intent is required");
            Objects.requireNonNull(testCase.criticality(), "case criticality is required");
            Objects.requireNonNull(testCase.expectedOutcome(), "expected outcome is required");
            if (testCase.turns().isEmpty()) {
                throw new IllegalArgumentException("evaluation case needs at least one turn");
            }
            if (testCase.expectedOutcome() == ExpectedOutcome.ANSWER
                    && testCase.requiredClaims().isEmpty() && testCase.forbiddenClaims().isEmpty()) {
                throw new IllegalArgumentException("answerable case needs a required or forbidden claim");
            }
            if (testCase.expectedOutcome() == ExpectedOutcome.CLARIFY_OR_ABSTAIN
                    && testCase.forbiddenClaims().isEmpty()) {
                throw new IllegalArgumentException("abstention case needs forbidden hallucination claims");
            }
            var requiredEvidence = switch (testCase.category()) {
                case DISCOVERY_IDENTITY, EXACT_TRIM_FACT, MEMORY_FOLLOW_UP, LONG_HISTORY ->
                        EvidenceKind.FORD_VEHICLE;
                case EXACT_PRICE -> EvidenceKind.STRUCTURED_PRICE;
                case PROMOTION_ELIGIBILITY -> EvidenceKind.STRUCTURED_PROMOTION;
                case COMPARISON -> EvidenceKind.COMPETITOR_COMPARISON;
                case CITATION -> EvidenceKind.SOURCE_CITATION;
                default -> null;
            };
            if (testCase.expectedOutcome() == ExpectedOutcome.ANSWER && requiredEvidence != null
                    && !testCase.requiredEvidenceKinds().contains(requiredEvidence)) {
                throw new IllegalArgumentException("catalog answer case needs reviewed evidence kinds");
            }
            if (testCase.baselineStatus() == BaselineStatus.KNOWN_GAP
                    && testCase.candidateIntent() != CandidateIntent.GAP_FIX) {
                throw new IllegalArgumentException("known baseline gap must be a candidate gap-fix case");
            }
            if (testCase.candidateIntent() == CandidateIntent.GAP_FIX
                    && testCase.baselineStatus() != BaselineStatus.KNOWN_GAP) {
                throw new IllegalArgumentException("candidate gap-fix case must identify a baseline gap");
            }
            if (testCase.category() == Category.PRIVACY_ISOLATION
                    && testCase.turns().stream().map(Turn::conversationKey).distinct().count() < 2) {
                throw new IllegalArgumentException("privacy-isolation case needs separate conversations");
            }
        }
        return List.copyOf(cases);
    }

    record Case(String id, Category category, BaselineStatus baselineStatus, CandidateIntent candidateIntent,
                Criticality criticality, ExpectedOutcome expectedOutcome, List<Turn> turns,
                List<String> requiredClaims, List<String> forbiddenClaims,
                List<EvidenceKind> requiredEvidenceKinds) {
        Case {
            turns = immutable(turns);
            requiredClaims = immutable(requiredClaims);
            forbiddenClaims = immutable(forbiddenClaims);
            requiredEvidenceKinds = immutable(requiredEvidenceKinds);
            if (requiredClaims.stream().anyMatch(claim -> claim == null || claim.isBlank())
                    || forbiddenClaims.stream().anyMatch(claim -> claim == null || claim.isBlank())) {
                throw new IllegalArgumentException("claims must not be blank");
            }
        }
    }

    record Turn(String conversationKey, String message, NaturalBuyerConversationScenario.Expectation expectation) {
        Turn {
            if (conversationKey == null || !SAFE_ID.matcher(conversationKey).matches()) {
                throw new IllegalArgumentException("synthetic conversation key is invalid");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("synthetic evaluation turn message is required");
            }
            Objects.requireNonNull(expectation, "turn expectation is required");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }

    enum Category {
        DISCOVERY_IDENTITY, NAME_INDEX_ROSTER, EXACT_TRIM_FACT, EXACT_PRICE, PROMOTION_ELIGIBILITY,
        COMPARISON, CITATION, MISSING_OR_AMBIGUOUS, MEMORY_FOLLOW_UP,
        PREFERENCE_CORRECTION, TOPIC_SWITCH_RETURN, LONG_HISTORY, PRIVACY_ISOLATION
    }

    enum BaselineStatus { EXPECTED_CORRECT, KNOWN_GAP, UNKNOWN }

    enum CandidateIntent { REGRESSION_CONTROL, GAP_FIX, COVERAGE_MEASUREMENT }

    enum Criticality { CRITICAL, HIGH, MEDIUM }

    enum ExpectedOutcome { ANSWER, CLARIFY_OR_ABSTAIN }

    enum EvidenceKind {
        FORD_VEHICLE, STRUCTURED_PRICE, STRUCTURED_PROMOTION,
        COMPETITOR_COMPARISON, SOURCE_CITATION
    }
}
