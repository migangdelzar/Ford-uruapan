package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.*;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ReplayPrefetchingChatCompletionPortTest {
    private static final String COMPARISON_MESSAGE =
            "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2";
    private static final List<VehicleNameIndexEntry> ENTRIES = List.of(
            new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
            new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

    @Test
    void delegatesWithMarkedEvidenceAndRecordsPrefetchTrace() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var receivedContext = new AtomicReference<ToolContext>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            received.set(request);
            receivedContext.set(context);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var request = request(COMPARISON_MESSAGE, collector);
        var context = new ToolContext(request.tenantId(), request.conversationId(), Set.of("compare_trims"));
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, new ObjectMapper());

        var response = port.complete(request, context);

        assertThat(response.content()).isEqualTo("respuesta Qwen");
        assertThat(received.get().memoryContext())
                .startsWith("existing memory")
                .contains("[REPLAY PREFETCHED COMPARISON EVIDENCE]", "priceDifferenceMxn", "132000");
        assertThat(received.get().message()).isEqualTo(request.message());
        assertThat(received.get().tenantId()).isEqualTo(request.tenantId());
        assertThat(received.get().conversationId()).isEqualTo(request.conversationId());
        assertThat(received.get().decision()).isSameAs(request.decision());
        assertThat(received.get().recentAssistantReplies()).isEqualTo(request.recentAssistantReplies());
        assertThat(received.get().traceCollector()).isSameAs(collector);
        assertThat(receivedContext.get()).isSameAs(context);
        assertThat(collector.snapshot()).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo("tool");
            assertThat(event.reasonCode()).isEqualTo("replay_prefetch_comparison");
            assertThat(event.toolName()).isEqualTo("compare_trims");
            assertThat(event.outcome()).isEqualTo("returned");
            assertThat(event.evidenceCount()).isEqualTo(1);
        });
    }

    @Test
    void delegatesUnresolvedPairWithoutChangingRequestOrTracingPrefetch() {
        var tools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            received.set(request);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var request = request("¿Qué precio tiene la Ranger?", collector);
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, new ObjectMapper());

        port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(received.get()).isSameAs(request);
        assertThat(collector.snapshot()).isEmpty();
    }

    @Test
    void skipsEvidenceThatExceedsTheBoundedPromptBlock() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(new CatalogTools.TrimComparison("RANGER", 2027, null, null,
                        List.of(new CatalogTools.TrimResult("XL", null, null, null, null),
                                new CatalogTools.TrimResult("XLT", null, null, null, null)),
                        List.of(new CatalogTools.TrimPairResult("XL", "XLT", "source_backed_differences_found",
                                BigDecimal.ONE, List.of(), null)), List.of(), "x".repeat(17_000)));
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            received.set(request);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var request = request(COMPARISON_MESSAGE, collector);
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, new ObjectMapper());

        port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(received.get()).isSameAs(request);
        assertThat(collector.snapshot()).isEmpty();
    }

    @Test
    void vehicleDetailsPrefetchUsesBuyerHistoryAndTracesGetVehicle() {
        var tools = mock(CatalogTools.class);
        var history = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Ranger 2027 XLT Gasolina 4x2\n[FIN DEL HISTORIAL]";
        var evidence = new VehicleEvidence("RANGER", 2027, "XLT GASOLINA 4X2", null, null, null,
                null, null, List.of(new VehicleFact("Motor", "2.3L", null, "https://ford.test/spec", null)), List.of());
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2")).thenReturn(evidence);
        var facts = new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> ENTRIES, tools);
        var comparisons = new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var receivedContext = new AtomicReference<ToolContext>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (req, ctx) -> {
            received.set(req); receivedContext.set(ctx);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "¿Qué fecha tiene el precio?",
                "BOT_ACTIVE", history, false,
                new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of()),
                null, List.of(), null, collector);
        var context = new ToolContext(request.tenantId(), request.conversationId(), Set.of("get_vehicle"));
        var response = new ReplayPrefetchingChatCompletionPort(delegate, comparisons, facts, new ObjectMapper())
                .complete(request, context);

        assertThat(response.content()).isEqualTo("respuesta Qwen");
        assertThat(received.get().message()).isEqualTo(request.message());
        assertThat(received.get().memoryContext()).contains(history, "[REPLAY PREFETCHED VEHICLE EVIDENCE]", "2.3L");
        assertThat(received.get().decision()).isSameAs(request.decision());
        assertThat(received.get().traceCollector()).isSameAs(collector);
        assertThat(received.get().tenantId()).isEqualTo(request.tenantId());
        assertThat(received.get().conversationId()).isEqualTo(request.conversationId());
        assertThat(receivedContext.get()).isSameAs(context);
        verify(tools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
        assertThat(collector.snapshot()).singleElement().satisfies(event -> {
            assertThat(event.toolName()).isEqualTo("get_vehicle");
            assertThat(event.route()).isEqualTo("replay_prefetch");
            assertThat(event.outcome()).isEqualTo("returned");
        });
    }

    @Test
    void unresolvedVehicleDetailsDelegatesUnchangedWithoutTrace() {
        var tools = mock(CatalogTools.class);
        var facts = new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> ENTRIES, tools);
        var comparisons = new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var request = request("¿Qué fecha tiene el precio?", new DecisionTraceCollector());
        var received = new AtomicReference<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (req, ctx) -> {
            received.set(req); return new AssistantResponse("respuesta", false, "ollama", List.of());
        };
        new ReplayPrefetchingChatCompletionPort(delegate, comparisons, facts, new ObjectMapper())
                .complete(request, new ToolContext(request.tenantId(), request.conversationId()));
        assertThat(received.get()).isSameAs(request);
        assertThat(request.traceCollector().snapshot()).isEmpty();
    }

    @Test
    void promotionPrefetchPreservesRequestAndContextAndRecordsPositiveEvidenceTrace() {
        var tools = mock(CatalogTools.class);
        var promotion = new CatalogTools.PromotionResult("promo", "RANGER", 2027, "Oferta", null, null,
                null, "MXN", null, "Aplican condiciones", null, null, null, "active",
                "https://ford.test/promo", null, null, null, null);
        when(tools.listPromotions("RANGER", 2027, null, false, 20, 0, "full", null))
                .thenReturn(new CatalogTools.PromotionSearch(1, 0, 20, false, List.of(promotion)));
        var history = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: RANGER 2027 XLT GASOLINA 4X2\n[FIN DEL HISTORIAL]";
        var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));
        var promotionPrefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools);
        var received = new AtomicReference<AssistantRequest>();
        var receivedContext = new AtomicReference<ToolContext>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (req, ctx) -> {
            received.set(req); receivedContext.set(ctx);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var original = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Ese monto corresponde a la XLT que acabamos de revisar?", "BOT_ACTIVE", history, false,
                new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of("respuesta previa"), "repair", collector);
        var context = new ToolContext(original.tenantId(), original.conversationId(), Set.of("list_promotions"));
        var comparisons = new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> entries, tools);
        var vehicleFacts = new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools);

        var response = new ReplayPrefetchingChatCompletionPort(delegate, comparisons, vehicleFacts,
                promotionPrefetcher, new ObjectMapper()).complete(original, context);

        assertThat(response.content()).isEqualTo("respuesta Qwen");
        assertThat(received.get().memoryContext()).startsWith(history).contains("[REPLAY PREFETCHED PROMOTION EVIDENCE]",
                "https://ford.test/promo", "Aplican condiciones");
        assertThat(received.get().tenantId()).isEqualTo(original.tenantId());
        assertThat(received.get().conversationId()).isEqualTo(original.conversationId());
        assertThat(received.get().message()).isEqualTo(original.message());
        assertThat(received.get().conversationState()).isEqualTo(original.conversationState());
        assertThat(received.get().containsSensitiveData()).isEqualTo(original.containsSensitiveData());
        assertThat(received.get().decision()).isSameAs(original.decision());
        assertThat(received.get().leadJourneyContext()).isEqualTo(original.leadJourneyContext());
        assertThat(received.get().recentAssistantReplies()).isEqualTo(original.recentAssistantReplies());
        assertThat(received.get().repairInstruction()).isEqualTo(original.repairInstruction());
        assertThat(received.get().traceCollector()).isSameAs(collector);
        assertThat(receivedContext.get()).isSameAs(context);
        verify(tools).listPromotions("RANGER", 2027, null, false, 20, 0, "full", null);
        assertThat(collector.snapshot()).singleElement().satisfies(event -> {
            assertThat(event.toolName()).isEqualTo("list_promotions");
            assertThat(event.outcome()).isEqualTo("returned");
            assertThat(event.evidenceCount()).isEqualTo(1);
            assertThat(event.reasonCode()).startsWith("replay_prefetch_");
        });
    }

    private static AssistantRequest request(String message, DecisionTraceCollector traceCollector) {
        return new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), message, "BOT_ACTIVE",
                "existing memory", false, new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, List.of()), null, List.of(), null, traceCollector);
    }

    private static CatalogTools.TrimComparison comparison() {
        return new CatalogTools.TrimComparison("RANGER", 2027, null, null,
                List.of(new CatalogTools.TrimResult("XL GASOLINA 4X2", BigDecimal.valueOf(775_000), "MXN",
                                null, null),
                        new CatalogTools.TrimResult("XLT GASOLINA 4X2", BigDecimal.valueOf(907_000), "MXN",
                                null, null)),
                List.of(new CatalogTools.TrimPairResult("XL GASOLINA 4X2", "XLT GASOLINA 4X2",
                        "source_backed_differences_found", BigDecimal.valueOf(132_000), List.of(), null)),
                List.of(), null);
    }
}
