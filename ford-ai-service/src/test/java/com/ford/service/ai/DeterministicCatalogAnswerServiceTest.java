package com.ford.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.DeterministicCatalogAnswerService;
import com.ford.service.ai.application.VehicleEntityResolver;
import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.VehicleEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeterministicCatalogAnswerServiceTest {

    @Test
    void checksOffersBeforeBasePriceForPriceRequest() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(1, 0, 3, List.of(
                new PromotionEvidence("promo-ranger-27", "Ranger", 2027, "Bono Ranger", "Bono publicado",
                        new BigDecimal("20000"), null, null, "MXN", "Aplican condiciones",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "active",
                        "https://www.forduruapan.mx/Promociones/Ranger/",
                        Instant.parse("2026-10-20T00:00:00Z"),
                        "https://www.forduruapan.mx/RANGER/27/"))));
        when(catalog.find(any())).thenReturn(Optional.of(new VehicleEvidence("Ranger", 2027,
                "XLT Gasolina 4x2", new BigDecimal("907000.00"), "MXN",
                "https://www.forduruapan.mx/RANGER/27/",
                "https://www.forduruapan.mx/RANGER/27/XLT/",
                Instant.parse("2026-10-20T00:00:00Z"), List.of(), List.of())));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?"))
                .orElseThrow();

        var calls = inOrder(catalog);
        calls.verify(catalog).promotions(any());
        calls.verify(catalog).find(any());
        assertThat(answer.content()).contains("Bono Ranger", "precio publicado", "907,000");
        assertThat(answer.content().indexOf("Bono Ranger"))
                .isLessThan(answer.content().indexOf("precio publicado"));
    }

    @Test
    void answersExactTrimMotorFromMatchingModelFactInsteadOfAdjacentTransmissionFact() {
        var catalog = mock(CatalogPort.class);
        var observedAt = Instant.parse("2026-10-20T00:00:00Z");
        var source = "https://www.forduruapan.mx/RANGER/27/Ficha-Tecnica/";
        when(catalog.find(any())).thenAnswer(invocation -> {
            var lookup = (com.ford.service.catalog.api.VehicleLookup) invocation.getArgument(0);
            if (lookup.trim() == null) {
                return Optional.of(new VehicleEvidence("RANGER", 2027, null, null, "MXN",
                        "https://www.forduruapan.mx/RANGER/27/", source, observedAt,
                        List.of(new com.ford.service.catalog.api.VehicleFact("Motor",
                                "Motor 2.3L I4 Gasolina TIVCT EcoBoost® con 270hp y 310 lb-pie de Torque"
                                        + " | Motor 2.0L Diésel Panther J con 168 hp y 299 lb-pie de Torque",
                                "", source, observedAt)), List.of()));
            }
            return Optional.of(new VehicleEvidence("RANGER", 2027, "XLT Gasolina 4x2", null, "MXN",
                    "https://www.forduruapan.mx/RANGER/27/", source, observedAt,
                    List.of(new com.ford.service.catalog.api.VehicleFact("Transmisión",
                            "Automática de 10 velocidades", "velocidades", source, observedAt)), List.of()));
        });
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var answer = service.tryAnswer(request("¿Qué motor tiene la Ranger 2027 XLT Gasolina 4x2?"))
                .orElseThrow();

        assertThat(answer.content()).contains("Ranger 2027", "XLT Gasolina 4x2", "2.3L", "270hp", "310 lb-pie")
                .doesNotContain("2.0L", "Diésel", "168 hp", "transmisión");
        assertThat(answer.content()).contains(source);
        assertThat(answer.sourceDocumentIds()).contains(source);
    }

    @Test
    void keepsVerifiedOfferWhenExactBasePriceIsMissing() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(1, 0, 3, List.of(
                new PromotionEvidence("promo-ranger-27", "Ranger", 2027, "Bono Ranger", "Bono publicado",
                        new BigDecimal("20000"), null, null, "MXN", "Aplican condiciones",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "active",
                        "https://www.forduruapan.mx/Promociones/Ranger/",
                        Instant.parse("2026-10-20T00:00:00Z"),
                        "https://www.forduruapan.mx/RANGER/27/"))));
        when(catalog.find(any())).thenReturn(Optional.empty());
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?"))
                .orElseThrow();

        assertThat(answer.content()).contains("Bono Ranger", "No pude confirmar un precio publicado exacto")
                .doesNotContain("No encontré promociones");
        verify(catalog).promotions(any());
        verify(catalog).find(any());
    }

    @Test
    void keepsVerifiedOfferWhenBasePriceLookupFails() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(1, 0, 3, List.of(
                new PromotionEvidence("promo-ranger-27", "Ranger", 2027, "Bono Ranger", "Bono publicado",
                        new BigDecimal("20000"), null, null, "MXN", "Aplican condiciones",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "active",
                        "https://www.forduruapan.mx/Promociones/Ranger/",
                        Instant.parse("2026-10-20T00:00:00Z"),
                        "https://www.forduruapan.mx/RANGER/27/"))));
        when(catalog.find(any())).thenThrow(new IllegalStateException("private price detail"));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?"))
                .orElseThrow();

        assertThat(answer.content()).contains("Bono Ranger", "No pude confirmar el precio publicado")
                .doesNotContain("private price detail");
        assertThat(answer.evidenceStatus()).isEqualTo(
                com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        assertThat(answer.sourceDocumentIds()).contains("https://www.forduruapan.mx/Promociones/Ranger/");
    }

    @Test
    void keepsVerifiedBasePriceWhenOfferLookupFails() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenThrow(new IllegalStateException("private database detail"));
        when(catalog.find(any())).thenReturn(Optional.of(new VehicleEvidence("Ranger", 2027,
                "XLT Gasolina 4x2", new BigDecimal("907000.00"), "MXN",
                "https://www.forduruapan.mx/RANGER/27/",
                "https://www.forduruapan.mx/RANGER/27/XLT/",
                Instant.parse("2026-10-20T00:00:00Z"), List.of(), List.of())));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);
        var trace = new DecisionTraceCollector();

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?", trace))
                .orElseThrow();

        assertThat(answer.content()).contains("907,000", "precio publicado")
                .doesNotContain("No encontré promociones", "private database detail");
        assertThat(answer.evidenceStatus()).isEqualTo(
                com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        assertThat(answer.sourceDocumentIds()).containsExactly("https://www.forduruapan.mx/RANGER/27/XLT/");
        assertThat(trace.snapshot().toString()).doesNotContain("private database detail");
        assertDeterministicTrace(trace, "returned", 1);
        verify(catalog).promotions(any());
        verify(catalog).find(any());
    }

    @Test
    void reportsFailureWhenBothIndependentPriceLookupsFail() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenThrow(new IllegalStateException("offer backend secret"));
        when(catalog.find(any())).thenThrow(new IllegalStateException("price backend secret"));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);
        var trace = new DecisionTraceCollector();

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?", trace))
                .orElseThrow();

        assertThat(answer.content()).contains("No pude confirmar la oferta ni el precio publicado")
                .doesNotContain("offer backend secret", "price backend secret");
        assertThat(answer.evidenceStatus()).isEqualTo(
                com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_FAILED);
        assertThat(answer.sourceDocumentIds()).isEmpty();
        assertDeterministicTrace(trace, "failed", 0);
    }

    @Test
    void rendersExactModelYearPriceFromCatalogWithoutModelGeneratedFacts() {
        var catalog = mock(CatalogPort.class);
        when(catalog.find(any())).thenReturn(java.util.Optional.of(new VehicleEvidence("Ranger", 2027, "XLT",
                new BigDecimal("799000.00"), "MXN", "https://www.forduruapan.mx/RANGER/27/?campaign=hidden#top",
                "https://user:secret@www.forduruapan.mx:8443/RANGER/27/Comparativo/XLT/?token=secret",
                java.time.Instant.parse("2026-10-20T00:00:00Z"),
                List.of(), List.of())));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);
        var trace = new DecisionTraceCollector();
        var request = request("¿Cuál es el precio de Ranger XLT 2027?", trace);

        var answer = service.tryAnswer(request).orElseThrow();

        assertThat(answer.provider()).isEqualTo("deterministic-catalog");
        assertThat(answer.content()).contains("Ranger 2027", "799,000", "forduruapan.mx");
        assertThat(answer.evidenceStatus()).isEqualTo(com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        assertThat(answer.sourceDocumentIds()).containsExactly(
                "https://www.forduruapan.mx/RANGER/27/Comparativo/XLT/");
        assertDeterministicTrace(trace, "returned", 1);
        verify(catalog).find(any());
    }

    @Test
    void rendersExactVariantPriceWhenResolvedTrimIsTheBaseTrim() {
        var catalog = mock(CatalogPort.class);
        when(catalog.find(any())).thenReturn(java.util.Optional.of(
                new VehicleEvidence("Ranger", 2027, "XLT Gasolina 4x2",
                        new BigDecimal("907000.00"), "MXN", "https://www.forduruapan.mx/RANGER/27/",
                        "https://www.forduruapan.mx/RANGER/27/Comparativo/XLT/",
                        java.time.Instant.parse("2026-10-20T00:00:00Z"), List.of(), List.of())));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var answer = service.tryAnswer(request(
                "Me interesa la Ranger 2027 XLT Gasolina 4x2. ¿Cuál es su precio publicado?"))
                .orElseThrow();

        assertThat(answer.provider()).isEqualTo("deterministic-catalog");
        assertThat(answer.content()).contains("Ranger 2027", "907,000", "XLT Gasolina 4x2");
        assertThat(answer.content()).doesNotContain("No pude confirmar");
        assertThat(answer.evidenceStatus()).isEqualTo(
                com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        verify(catalog).find(any());
    }

    @Test
    void doesNotQueryOrGuessWhenVehicleReferenceIsAmbiguous() {
        var catalog = mock(CatalogPort.class);
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        assertThat(service.tryAnswer(request("¿Cuál es el precio de Ranger o Bronco 2027?"))).isEmpty();
        verifyNoInteractions(catalog);
    }

    @Test
    void rendersModelPromotionDirectlyFromCatalog() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(1, 0, 3, List.of(
                new PromotionEvidence("promo-ranger-27", "Ranger", 2027, "Bono Ranger", "Bono publicado",
                        new BigDecimal("20000"), null, null, "MXN", "Aplican condiciones",
                        java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 11, 1), "active",
                        "https://www.forduruapan.mx/Promociones/", java.time.Instant.parse("2026-10-20T00:00:00Z"),
                        "https://www.forduruapan.mx/RANGER/27/"))));
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);

        var trace = new DecisionTraceCollector();
        when(catalog.promotions(any())).thenReturn(new PromotionPage(1, 0, 3, List.of(
                new PromotionEvidence("promo-ranger-27", "Ranger", 2027, "Bono Ranger", "Bono publicado",
                        new BigDecimal("20000"), null, null, "MXN", "Aplican condiciones",
                        java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 11, 1), "active",
                        "https://www.forduruapan.mx/Promociones/?campaign=hidden",
                        java.time.Instant.parse("2026-10-20T00:00:00Z"),
                        "https://www.forduruapan.mx/Promociones/"))));

        var answer = service.tryAnswer(request("¿Qué promociones hay para Ranger?", trace)).orElseThrow();

        assertThat(answer.provider()).isEqualTo("deterministic-catalog");
        assertThat(answer.content()).contains("Bono Ranger", "forduruapan.mx");
        assertThat(answer.evidenceStatus()).isEqualTo(
                com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        assertThat(answer.sourceDocumentIds()).containsExactly("https://www.forduruapan.mx/Promociones/");
        assertDeterministicTrace(trace, "returned", 1);
        assertThat(trace.snapshot()).anySatisfy(event -> {
            assertThat(event.stage()).isEqualTo("tool");
            assertThat(event.toolName()).isEqualTo("list_promotions");
            assertThat(event.outcome()).isEqualTo("returned");
            assertThat(event.evidenceCount()).isEqualTo(1);
        });
        verify(catalog).promotions(any());
    }

    @Test
    void recordsEmptyTraceWhenExactCatalogPriceIsMissing() {
        var catalog = mock(CatalogPort.class);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(0, 0, 3, List.of()));
        when(catalog.find(any())).thenReturn(java.util.Optional.empty());
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new DeterministicCatalogAnswerService(catalog,
                new com.ford.service.ai.application.CatalogFactResponseRenderer(
                        new DefaultResourceLoader(), objectMapper), objectMapper);
        var trace = new DecisionTraceCollector();

        var answer = service.tryAnswer(request("¿Cuál es el precio de Ranger XLT 2027?", trace)).orElseThrow();

        assertThat(answer.content()).contains("No pude confirmar un precio publicado exacto");
        assertThat(answer.sourceDocumentIds()).isEmpty();
        assertDeterministicTrace(trace, "empty", 0);
    }

    private static void assertDeterministicTrace(DecisionTraceCollector trace, String outcome, int evidenceCount) {
        assertThat(trace.snapshot()).filteredOn(event -> event.stage().equals("answer")).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo("answer");
            assertThat(event.reasonCode()).isEqualTo("deterministic_catalog_lookup");
            assertThat(event.route()).isEqualTo("deterministic-catalog");
            assertThat(event.modelRole()).isEqualTo("none");
            assertThat(event.toolName()).isEmpty();
            assertThat(event.outcome()).isEqualTo(outcome);
            assertThat(event.evidenceCount()).isEqualTo(evidenceCount);
        });
        assertThat(trace.snapshot().toString()).doesNotContain("¿", "Ranger", "799,000", "forduruapan.mx");
    }

    private static AssistantRequest request(String message) {
        return request(message, null);
    }

    private static AssistantRequest request(String message, DecisionTraceCollector traceCollector) {
        var tenantId = UUID.randomUUID();
        var context = new ClassificationContext(tenantId, UUID.randomUUID(), message, null, "BOT_ACTIVE", false);
        var decision = new RulesConversationDecisionAdapter(new com.ford.service.ai.application.IntentRouter())
                .decide(context).withVehicleEntities(new VehicleEntityResolver().resolve(message, null));
        return new AssistantRequest(tenantId, context.conversationId(), message, "BOT_ACTIVE", null,
                false, decision, null, List.of(), null, traceCollector);
    }
}
