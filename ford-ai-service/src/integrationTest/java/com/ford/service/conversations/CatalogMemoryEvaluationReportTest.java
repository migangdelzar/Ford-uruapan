package com.ford.service.conversations;

import com.ford.service.observability.AiProviderCallObserver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMemoryEvaluationReportTest {
    @Test
    void scoresReviewedClaimsAndToolEvidenceWithoutRetainingRawAnswer() throws Exception {
        var testCase = caseById("f250-xlt-powertrain");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "La F-250 XLT tiene 475 HP y 1,050 lb-pie de torque, según https://ford.example/catalogo.pdf#page=7.",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE,
                        CatalogMemoryEvaluationScenario.EvidenceKind.SOURCE_CITATION), true);

        assertThat(result.correct()).isTrue();
        assertThat(result.criticalError()).isFalse();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.toString()).doesNotContain("475 HP", "1,050 lb-pie");
    }

    @Test
    void acceptsThousandsSeparatorVariantsAndVisibleCitationFromRealToolTrace() throws Exception {
        var testCase = caseById("f250-xlt-powertrain");
        var traces = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1));
        var evidence = CatalogMemoryEvaluationReport.evidenceKinds(traces);
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "La F-250 XLT tiene: Potencia: 475 hp; Torque: 1050 lb-pie. "
                        + "Fuente https://www.forduruapan.mx/Assets/ModelosNuevos/pdf/catalogos/F25026.Pdf#page=7",
                evidence, true);

        assertThat(result.correct()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void acceptsCorrectAnswerThatEndsWithAFollowUpQuestion() throws Exception {
        var testCase = caseById("f250-platinum-powertrain");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "La F-250 2026 Platinum Plus tiene 500 hp y 1200 lb-pie. "
                        + "Fuente https://www.forduruapan.mx/F25026.pdf. ¿Desea conocer otras versiones?",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true);

        assertThat(result.correct()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void scoresExactInventoryAbstentionWithoutTreatingModelYearCoverageAsStockClaim() throws Exception {
        var testCase = caseById("unsupported-fact-abstention");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "El catálogo no confirma disponibilidad exacta de la Ranger XLT en inventario. "
                        + "Los modelos disponibles son RANGER 2026 y 2027, pero sin especificar versión XLT ni stock real. "
                        + "¿Le interesa revisar las opciones disponibles?",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true);

        assertThat(result.correct()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void marksForbiddenWrongTrimOrPromotionClaimsAsCriticalErrors() throws Exception {
        var testCase = caseById("f250-platinum-powertrain");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "La Platinum Plus entrega 475 HP y 1,050 lb-pie.",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true);

        assertThat(result.correct()).isFalse();
        assertThat(result.criticalError()).isTrue();
        assertThat(result.reasonCodes()).contains("FORBIDDEN_CLAIM_PRESENT", "REQUIRED_EVIDENCE_MISSING");
    }

    @Test
    void doesNotTreatSuccessfulVehicleRetrievalAsAVisibleCitation() throws Exception {
        var testCase = caseById("f250-xlt-powertrain");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                "La F-250 XLT tiene 475 HP y 1,050 lb-pie de torque.",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE,
                        CatalogMemoryEvaluationScenario.EvidenceKind.SOURCE_CITATION), true);

        assertThat(result.correct()).isFalse();
        assertThat(result.reasonCodes()).contains("REQUIRED_EVIDENCE_MISSING");
    }

    @Test
    void pairedAcceptanceRejectsCandidateRegressionsAndUnfixedTargetGaps() throws Exception {
        var allCases = CatalogMemoryEvaluationScenario.load(new ObjectMapper());
        var control = caseById("ranger-discovery-2027");
        var gap = caseById("f250-xlt-powertrain");
        var baseline = List.of(
                CatalogMemoryEvaluationReport.evaluate(control, "Ranger 2027 es una opción Ford.",
                        Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true),
                CatalogMemoryEvaluationReport.evaluate(gap, "No encuentro datos confirmados para esa versión.",
                        Set.of(), true));
        var candidate = List.of(
                CatalogMemoryEvaluationReport.evaluate(control, "Ranger 2026 es una opción Ford.",
                        Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true),
                CatalogMemoryEvaluationReport.evaluate(gap, "No puedo confirmar la potencia.", Set.of(), true));

        var paired = CatalogMemoryEvaluationReport.compare(allCases, baseline, candidate);

        assertThat(paired.passed()).isFalse();
        assertThat(paired.regressions()).containsExactly("ranger-discovery-2027");
        assertThat(paired.unfixedGaps()).containsExactly("f250-xlt-powertrain");
        assertThat(paired.missingCaseIds()).isNotEmpty();
    }

    @Test
    void unavailableTokenUsageRemainsUnscoredAndDoesNotAffectAnswerCorrectness() throws Exception {
        var testCase = caseById("ranger-discovery-2027");
        var result = CatalogMemoryEvaluationReport.evaluate(testCase, "Ranger 2027 es una opción Ford.",
                Set.of(CatalogMemoryEvaluationScenario.EvidenceKind.FORD_VEHICLE), true);

        assertThat(result.correct()).isTrue();
        assertThat(AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE.name()).isEqualTo("UNAVAILABLE");
    }

    private static CatalogMemoryEvaluationScenario.Case caseById(String id) throws Exception {
        return CatalogMemoryEvaluationScenario.load(new ObjectMapper()).stream()
                .filter(testCase -> testCase.id().equals(id)).findFirst().orElseThrow();
    }
}
