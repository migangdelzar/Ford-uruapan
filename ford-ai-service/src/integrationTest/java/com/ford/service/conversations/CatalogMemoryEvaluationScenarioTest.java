package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CatalogMemoryEvaluationScenarioTest {
    @Test
    void providesReviewedCatalogAndMemoryEvaluationCases() throws Exception {
        var cases = CatalogMemoryEvaluationScenario.load(new ObjectMapper());

        assertThat(cases).hasSizeGreaterThanOrEqualTo(12);
        assertThat(cases).extracting(CatalogMemoryEvaluationScenario.Case::category)
                .contains(CatalogMemoryEvaluationScenario.Category.EXACT_TRIM_FACT,
                        CatalogMemoryEvaluationScenario.Category.EXACT_PRICE,
                        CatalogMemoryEvaluationScenario.Category.PROMOTION_ELIGIBILITY,
                        CatalogMemoryEvaluationScenario.Category.COMPARISON,
                        CatalogMemoryEvaluationScenario.Category.MEMORY_FOLLOW_UP,
                        CatalogMemoryEvaluationScenario.Category.PREFERENCE_CORRECTION,
                        CatalogMemoryEvaluationScenario.Category.TOPIC_SWITCH_RETURN,
                        CatalogMemoryEvaluationScenario.Category.LONG_HISTORY,
                        CatalogMemoryEvaluationScenario.Category.PRIVACY_ISOLATION);
        assertThat(cases.stream().filter(testCase -> testCase.candidateIntent()
                == CatalogMemoryEvaluationScenario.CandidateIntent.GAP_FIX)).hasSize(3);
        assertThat(cases.stream().filter(testCase -> testCase.category()
                == CatalogMemoryEvaluationScenario.Category.PRIVACY_ISOLATION)
                .flatMap(testCase -> testCase.turns().stream())
                .map(CatalogMemoryEvaluationScenario.Turn::conversationKey).distinct())
                .hasSize(2);
    }

    @Test
    void includesNameOnlyRosterCaseThatDoesNotRequireCatalogFactEvidence() throws Exception {
        var testCase = CatalogMemoryEvaluationScenario.load(new ObjectMapper()).stream()
                .filter(candidate -> candidate.id().equals("name-index-roster"))
                .findFirst().orElseThrow();

        assertThat(testCase.category()).isEqualTo(CatalogMemoryEvaluationScenario.Category.NAME_INDEX_ROSTER);
        assertThat(testCase.expectedOutcome()).isEqualTo(CatalogMemoryEvaluationScenario.ExpectedOutcome.ANSWER);
        assertThat(testCase.requiredEvidenceKinds()).isEmpty();
        assertThat(testCase.requiredClaims()).contains("Ranger 2027", "XLT Gasolina 4x2");
        assertThat(testCase.forbiddenClaims()).contains("hay unidades", "disponible en inventario");
    }

    @Test
    void rejectsAnswerableCasesWithoutReviewedClaims() {
        var invalid = new CatalogMemoryEvaluationScenario.Case("empty-answer", 
                CatalogMemoryEvaluationScenario.Category.EXACT_TRIM_FACT,
                CatalogMemoryEvaluationScenario.BaselineStatus.UNKNOWN,
                CatalogMemoryEvaluationScenario.CandidateIntent.COVERAGE_MEASUREMENT,
                CatalogMemoryEvaluationScenario.Criticality.HIGH,
                CatalogMemoryEvaluationScenario.ExpectedOutcome.ANSWER,
                List.of(new CatalogMemoryEvaluationScenario.Turn("buyer-a", "¿Qué motor tiene?",
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT)),
                List.of(), List.of(), List.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CatalogMemoryEvaluationScenario.validateCases(List.of(invalid)))
                .withMessageContaining("answerable case needs a required or forbidden claim");
    }

    @Test
    void rejectsDuplicateCaseIdentifiers() {
        var testCase = new CatalogMemoryEvaluationScenario.Case("duplicate-id",
                CatalogMemoryEvaluationScenario.Category.EXACT_TRIM_FACT,
                CatalogMemoryEvaluationScenario.BaselineStatus.UNKNOWN,
                CatalogMemoryEvaluationScenario.CandidateIntent.COVERAGE_MEASUREMENT,
                CatalogMemoryEvaluationScenario.Criticality.HIGH,
                CatalogMemoryEvaluationScenario.ExpectedOutcome.ANSWER,
                List.of(new CatalogMemoryEvaluationScenario.Turn("buyer-a", "¿Qué motor tiene?",
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT)),
                List.of("motor"), List.of(), List.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CatalogMemoryEvaluationScenario.validateCases(List.of(testCase, testCase)))
                .withMessageContaining("duplicate evaluation case ID");
    }

    @Test
    void rejectsExactFactCasesWithoutReviewedEvidenceRequirements() {
        var invalid = new CatalogMemoryEvaluationScenario.Case("missing-evidence",
                CatalogMemoryEvaluationScenario.Category.EXACT_TRIM_FACT,
                CatalogMemoryEvaluationScenario.BaselineStatus.UNKNOWN,
                CatalogMemoryEvaluationScenario.CandidateIntent.COVERAGE_MEASUREMENT,
                CatalogMemoryEvaluationScenario.Criticality.HIGH,
                CatalogMemoryEvaluationScenario.ExpectedOutcome.ANSWER,
                List.of(new CatalogMemoryEvaluationScenario.Turn("buyer-a", "¿Qué motor tiene la Ranger?",
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT)),
                List.of("Ranger"), List.of(), List.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CatalogMemoryEvaluationScenario.validateCases(List.of(invalid)))
                .withMessageContaining("catalog answer case needs reviewed evidence kinds");
    }

    @Test
    void requiresExplicitForbiddenClaimsForAbstentionCases() {
        var invalid = new CatalogMemoryEvaluationScenario.Case("empty-abstention-rubric",
                CatalogMemoryEvaluationScenario.Category.MISSING_OR_AMBIGUOUS,
                CatalogMemoryEvaluationScenario.BaselineStatus.UNKNOWN,
                CatalogMemoryEvaluationScenario.CandidateIntent.COVERAGE_MEASUREMENT,
                CatalogMemoryEvaluationScenario.Criticality.HIGH,
                CatalogMemoryEvaluationScenario.ExpectedOutcome.CLARIFY_OR_ABSTAIN,
                List.of(new CatalogMemoryEvaluationScenario.Turn("buyer-a", "¿Cuál es el inventario?",
                        NaturalBuyerConversationScenario.Expectation.UNAVAILABLE_CAPABILITY)),
                List.of(), List.of(), List.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CatalogMemoryEvaluationScenario.validateCases(List.of(invalid)))
                .withMessageContaining("abstention case needs forbidden hallucination claims");
    }
}
