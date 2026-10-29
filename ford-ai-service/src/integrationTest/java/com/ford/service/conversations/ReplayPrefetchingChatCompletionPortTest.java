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
    void bindsLiveComparisonEnvelopeToEnhancedRequestWithoutModelToolTrace() {
        var tools = mock(CatalogTools.class);
        var original = comparison();
        var sourcedTrims = original.trims().stream().map(trim -> new CatalogTools.TrimResult(trim.trim(),
                trim.price(), trim.currency(), "https://www.forduruapan.mx/RANGER/27/Comparativo/", null)).toList();
        when(tools.compareTrims("RANGER", 2027, List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(new CatalogTools.TrimComparison(original.model(), original.year(), null, null,
                        sourcedTrims, original.comparisons(), List.of(), null));
        var received = new AtomicReference<AssistantRequest>();
        var receivedContext = new AtomicReference<ToolContext>();
        var collector = new DecisionTraceCollector();
        var request = request(COMPARISON_MESSAGE, collector);
        var context = new ToolContext(request.tenantId(), request.conversationId(), Set.of("compare_trims"), collector);
        var port = new ReplayPrefetchingChatCompletionPort((req, ctx) -> {
            received.set(req);
            receivedContext.set(ctx);
            return new AssistantResponse("respuesta live", false, "ollama", List.of());
        }, new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> ENTRIES, tools),
                null, null, new ObjectMapper(), null, () -> "a".repeat(64));

        port.complete(request, context);

        assertThat(receivedContext.get().prefetchedComparison()).isNotNull();
        assertThat(receivedContext.get().authorizedToolNames()).isEqualTo(context.authorizedToolNames());
        assertThat(receivedContext.get().traceCollector()).isSameAs(collector);
        var tracker = new ToolResultTracker();
        assertThat(tracker.acceptPrefetchedComparison(received.get(), receivedContext.get(), Set.of("compare_trims")))
                .isTrue();
        assertThat(new ToolResultTracker().acceptPrefetchedComparison(request, receivedContext.get(), Set.of("compare_trims")))
                .isFalse();
        assertThat(tracker.snapshot().calls()).isEmpty();
        assertThat(collector.snapshot()).singleElement().satisfies(event ->
                assertThat(event.reasonCode()).isEqualTo("replay_prefetch_comparison"));
    }

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
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var request = request(COMPARISON_MESSAGE, collector);
        var context = new ToolContext(request.tenantId(), request.conversationId(), Set.of("compare_trims"));
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);

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
        assertThat(precisionEvidence.currentTurnToolTraces(request.conversationId())).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
        assertThat(precisionEvidence.currentTurnEvidence(request.conversationId()))
                .anySatisfy(text -> assertThat(text).contains("RANGER", "XLT GASOLINA 4X2", "132000"));
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
    void exposesRecordedSeedTracesOnlyForExactSeedTurnAndMessage() {
        var tools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var trace = new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL);
        var seed = new LocalQwenTranscriptSeedReader.SeedReply(1, COMPARISON_MESSAGE,
                "Respuesta previamente validada", List.of(trace));
        var seeded = new LocalQwenSeededChatCompletionPort(
                (request, context) -> new AssistantResponse("live", false, "ollama", List.of()),
                List.of(seed), 1);
        var port = new ReplayPrefetchingChatCompletionPort(seeded, prefetcher, new ObjectMapper());

        assertThat(port.seedToolTracesForTurn(1, COMPARISON_MESSAGE)).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED));
        assertThat(port.seedToolTracesForTurn(1, "different message")).isEmpty();
        assertThat(port.seedToolTracesForTurn(2, COMPARISON_MESSAGE)).isEmpty();
    }

    @Test
    void doesNotRunCatalogPrefetchForRecordedSeedReply() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var replies = List.of(new LocalQwenTranscriptSeedReader.SeedReply(1, COMPARISON_MESSAGE,
                "Respuesta grabada", List.of()));
        var liveCalls = new java.util.concurrent.atomic.AtomicInteger();
        var seeded = new LocalQwenSeededChatCompletionPort((request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("respuesta live", false, "ollama", List.of());
        }, replies, List.of(new NaturalBuyerConversationScenario.Turn(1, COMPARISON_MESSAGE,
                NaturalBuyerConversationScenario.Expectation.COMPARISON)), 1, Set.of());
        var port = new ReplayPrefetchingChatCompletionPort(seeded, prefetcher, new ObjectMapper());
        var request = request(COMPARISON_MESSAGE, new DecisionTraceCollector());

        var response = port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(response.content()).isEqualTo("Respuesta grabada");
        assertThat(liveCalls).hasValue(0);
        verify(tools, org.mockito.Mockito.never()).compareTrims(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void bypassesReplayPrefetchForMemoryCompactionRequests() {
        var tools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            received.set(request);
            return new AssistantResponse("compact summary", false, "ollama", List.of());
        };
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "Summarize prior conversation mentioning Ranger comparison", "MEMORY_COMPACTION", false);
        var evidence = new LocalReplayPrecisionEvidence();
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), evidence);

        var response = port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(response.content()).isEqualTo("compact summary");
        assertThat(received.get()).isSameAs(request);
        assertThat(evidence.currentTurnToolTraces(request.conversationId())).isEmpty();
        assertThat(evidence.currentTurnComparisonStatus(request.conversationId()))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.NOT_A_COMPARISON);
        verify(tools, org.mockito.Mockito.never()).compareTrims(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void injectsPairVehicleFactsAndUnknownNotAbsentGuidanceForDimensionFollowUp() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        when(tools.getVehicle("RANGER", 2027, "XL GASOLINA 4X2"))
                .thenReturn(new com.ford.service.catalog.api.VehicleEvidence("RANGER", 2027,
                        "XL GASOLINA 4X2", null, null, null, null, null,
                        List.of(new com.ford.service.catalog.api.VehicleFact("spec", "price", "775000", null, null)),
                        List.of()));
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(new com.ford.service.catalog.api.VehicleEvidence("RANGER", 2027,
                        "XLT GASOLINA 4X2", null, null, null, null, null,
                        List.of(new com.ford.service.catalog.api.VehicleFact("spec", "price", "907000", null, null)),
                        List.of()));
        var resolver = new LocalComparisonPairResolver();
        var prefetcher = new LocalReplayComparisonPrefetcher(resolver, () -> ENTRIES, tools);
        var vehicleFacts = new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> ENTRIES, tools);
        var received = new AtomicReference<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            received.set(request);
            return new AssistantResponse("No puedo verificar las dimensiones con estos datos.", false, "ollama", List.of());
        };
        var request = request("¿La fuente del catálogo trae dimensiones para esas versiones?", new DecisionTraceCollector());
        var prior = request("Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2",
                new DecisionTraceCollector());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, vehicleFacts, null,
                new ObjectMapper(), new LocalReplayPrecisionEvidence());
        port.complete(prior, new ToolContext(prior.tenantId(), prior.conversationId()));

        port.complete(new AssistantRequest(request.tenantId(), prior.conversationId(), request.message(),
                        request.conversationState(), request.memoryContext(), request.containsSensitiveData(),
                        request.decision(), request.leadJourneyContext(), request.recentAssistantReplies(),
                        request.repairInstruction(), request.traceCollector()),
                new ToolContext(request.tenantId(), prior.conversationId()));

        assertThat(received.get().memoryContext()).contains("[REPLAY PREFETCHED VEHICLE EVIDENCE]",
                "XL GASOLINA 4X2", "XLT GASOLINA 4X2", "Missing fields are unknown, not proof of absence",
                "do not claim the catalog has no dimensions");
        verify(tools).getVehicle("RANGER", 2027, "XL GASOLINA 4X2");
        verify(tools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
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
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);

        port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(received.get()).isSameAs(request);
        assertThat(collector.snapshot()).isEmpty();
        assertThat(precisionEvidence.currentTurnComparisonStatus(request.conversationId()))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.EVIDENCE_TOO_LARGE);
    }

    @Test
    void recordsUnresolvedComparisonStatusAndClearsPriorTurnEvidence() {
        var tools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) ->
                new AssistantResponse("No puedo confirmar la comparación.", false, "ollama", List.of());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        precisionEvidence.beginTurn(conversationId);
        precisionEvidence.recordToolEvidence(conversationId, "compare_trims", 1, "prior turn evidence");
        precisionEvidence.recordComparisonStatus(conversationId,
                LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.INJECTED);
        var request = request(tenantId, conversationId,
                "¿La comparación incluye alguna característica de seguridad para cada una?",
                new DecisionTraceCollector());

        port.complete(request, new ToolContext(tenantId, conversationId));

        assertThat(precisionEvidence.currentTurnComparisonStatus(conversationId))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.PAIR_UNRESOLVED);
        assertThat(precisionEvidence.currentTurnEvidence(conversationId)).isEmpty();
        assertThat(precisionEvidence.currentTurnToolTraces(conversationId)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(tools);
    }

    @Test
    void preservesExactBuyerComparisonPairThroughTurnThirtyHistoryWindow() throws Exception {
        var catalogTools = mock(CatalogTools.class);
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) ->
                new AssistantResponse("Respuesta cautelosa.", false, "ollama", List.of());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var turns = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper());

        for (var turn : turns.subList(0, 30)) {
            port.complete(request(tenantId, conversationId, turn.message(), new DecisionTraceCollector()),
                    new ToolContext(tenantId, conversationId, Set.of("compare_trims")));
        }

        assertThat(precisionEvidence.currentTurnComparisonStatus(conversationId))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.INJECTED);
        assertThat(precisionEvidence.currentTurnToolTraces(conversationId)).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
        verify(catalogTools, org.mockito.Mockito.atLeastOnce()).compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary");
    }

    @Test
    void retainsNearestExactBuyerPairAfterBoundedHistoryEvictsItsOriginalMessage() {
        var catalogTools = mock(CatalogTools.class);
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) ->
                new AssistantResponse("Respuesta sintética.", false, "ollama", List.of());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        port.complete(request(tenantId, conversationId,
                        "Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.",
                        new DecisionTraceCollector()), new ToolContext(tenantId, conversationId));
        for (var index = 0; index < 25; index++) {
            port.complete(request(tenantId, conversationId, "Gracias, sigo revisando opción " + index + ".",
                    new DecisionTraceCollector()), new ToolContext(tenantId, conversationId));
        }

        port.complete(request(tenantId, conversationId,
                        "¿La comparación incluye alguna característica de seguridad para cada una?",
                        new DecisionTraceCollector()), new ToolContext(tenantId, conversationId));

        assertThat(precisionEvidence.currentTurnComparisonStatus(conversationId))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.INJECTED);
        assertThat(precisionEvidence.currentTurnToolTraces(conversationId)).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
    }

    @Test
    void resolvesTurnTwentyNineSafetyFollowUpFromBuyerPairAndInjectsCurrentTurnEvidence() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison());
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var delegatedRequests = new java.util.ArrayList<AssistantRequest>();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) -> {
            delegatedRequests.add(request);
            return new AssistantResponse(
                    "No tengo información de seguridad confirmada para distinguir las dos versiones.",
                    false, "ollama", List.of());
        };
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var firstCollector = new DecisionTraceCollector();
        var followUpCollector = new DecisionTraceCollector();
        var first = request(tenantId, conversationId,
                "Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.", firstCollector);
        var followUp = request(tenantId, conversationId,
                "¿La comparación incluye alguna característica de seguridad para cada una?", followUpCollector);

        port.complete(first, new ToolContext(tenantId, conversationId, Set.of("compare_trims")));
        var answer = port.complete(followUp, new ToolContext(tenantId, conversationId, Set.of("compare_trims")));

        assertThat(answer.content()).contains("No tengo información de seguridad confirmada");
        assertThat(delegatedRequests).hasSize(2);
        assertThat(delegatedRequests.get(1).memoryContext())
                .contains("[REPLAY PREFETCHED COMPARISON EVIDENCE]", "RANGER", "XLT GASOLINA 4X2");
        verify(tools, org.mockito.Mockito.times(2)).compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary");
        assertThat(precisionEvidence.currentTurnToolTraces(conversationId)).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
        assertThat(precisionEvidence.currentTurnEvidence(conversationId))
                .anySatisfy(text -> assertThat(text).contains("RANGER", "XL GASOLINA 4X2", "XLT GASOLINA 4X2"));
        assertThat(followUpCollector.snapshot()).anySatisfy(event -> {
            assertThat(event.toolName()).isEqualTo("compare_trims");
            assertThat(event.reasonCode()).isEqualTo("replay_prefetch_comparison");
            assertThat(event.outcome()).isEqualTo("returned");
            assertThat(event.evidenceCount()).isEqualTo(1);
        });
        assertThat(precisionEvidence.currentTurnComparisonStatus(conversationId))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.INJECTED);
        assertThat(LocalReplaySafetyAssertions.evaluate(new NaturalBuyerConversationScenario.Turn(29,
                        "¿La comparación incluye alguna característica de seguridad para cada una?",
                        NaturalBuyerConversationScenario.Expectation.COMPARISON), answer.content(),
                precisionEvidence.currentTurnToolTraces(conversationId), List.of(), ENTRIES, List.of(), true))
                .isEmpty();
    }

    @Test
    void recordsCurrentTurnStatusWhenComparisonCatalogResultIsIncomplete() {
        var tools = mock(CatalogTools.class);
        when(tools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(null);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var precisionEvidence = new LocalReplayPrecisionEvidence();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (request, context) ->
                new AssistantResponse("respuesta cautelosa", false, "ollama", List.of());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, prefetcher, null, null,
                new ObjectMapper(), precisionEvidence);
        var request = request(COMPARISON_MESSAGE, new DecisionTraceCollector());

        port.complete(request, new ToolContext(request.tenantId(), request.conversationId()));

        assertThat(precisionEvidence.currentTurnComparisonStatus(request.conversationId()))
                .isEqualTo(LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.CATALOG_RESULT_INCOMPLETE);
        assertThat(precisionEvidence.currentTurnToolTraces(request.conversationId())).isEmpty();
        assertThat(precisionEvidence.currentTurnEvidence(request.conversationId())).isEmpty();
    }

    @Test
    void vehicleDetailsPrefetchUsesCurrentBuyerIdentityAndTracesGetVehicle() {
        var tools = mock(CatalogTools.class);
        var history = "assistant memory must not resolve vehicle identity";
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
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué fecha tiene el precio de Ranger 2027 XLT Gasolina 4x2?",
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
    void resolvesExactIdentityFromEarlierBuyerTurnWhenAssistantMemoryOmitsIt() {
        var tools = mock(CatalogTools.class);
        var evidence = new VehicleEvidence("RANGER", 2027, "XLT GASOLINA 4X2", null, null, null,
                null, null, List.of(new VehicleFact("Seguridad", "ABS", null, "https://ford.test/spec", null)),
                List.of());
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2")).thenReturn(evidence);
        var facts = new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> ENTRIES, tools);
        var comparisons = new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> ENTRIES, tools);
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (req, ctx) ->
                new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        var port = new ReplayPrefetchingChatCompletionPort(delegate, comparisons, facts, new ObjectMapper());
        var conversationId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var firstBuyerMessage = "Revisemos Ranger 2027 XLT Gasolina 4x2 del catálogo.";
        port.complete(new AssistantRequest(tenantId, conversationId, firstBuyerMessage, "BOT_ACTIVE",
                        "memory without catalog identity", false,
                        new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                        null, List.of(), null, new DecisionTraceCollector()),
                new ToolContext(tenantId, conversationId));

        var followUp = new AssistantRequest(tenantId, conversationId,
                "¿Y qué dato de seguridad aparece para esa misma versión?", "BOT_ACTIVE",
                "memory without catalog identity", false,
                new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of(), null, new DecisionTraceCollector());
        var answer = port.complete(followUp, new ToolContext(tenantId, conversationId));

        assertThat(answer.content()).isEqualTo("respuesta Qwen");
        verify(tools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
        assertThat(followUp.traceCollector().snapshot()).singleElement()
                .satisfies(event -> assertThat(event.reasonCode()).isEqualTo("replay_prefetch_vehicle_evidence"));
    }

    @Test
    void promotionPrefetchPreservesRequestAndContextAndRecordsPositiveEvidenceTrace() {
        var tools = mock(CatalogTools.class);
        var promotion = new CatalogTools.PromotionResult("promo", "RANGER", 2027, "Oferta", null, null,
                null, "MXN", null, "Aplican condiciones", null, null, null, "active",
                "https://ford.test/promo", null, null, null, null);
        when(tools.listPromotions("RANGER", 2027, null, false, 0, 20, "full", "RANGER 2027"))
                .thenReturn(new CatalogTools.PromotionSearch(1, 0, 20, false, List.of(promotion)));
        var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));
        var promotionPrefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools);
        var received = new AtomicReference<AssistantRequest>();
        var receivedContext = new AtomicReference<ToolContext>();
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var delegate = (com.ford.service.ai.application.port.out.ChatCompletionPort) (req, ctx) -> {
            received.set(req); receivedContext.set(ctx);
            return new AssistantResponse("respuesta Qwen", false, "ollama", List.of());
        };
        var collector = new DecisionTraceCollector();
        var prefetchPort = new ReplayPrefetchingChatCompletionPort(delegate,
                new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> entries, tools),
                new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools),
                promotionPrefetcher, new ObjectMapper());
        var firstBuyerTurn = new AssistantRequest(tenantId, conversationId,
                "Revisemos Ranger 2027 XLT Gasolina 4x2.", "BOT_ACTIVE", "assistant-authored memory ignored",
                false, new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of(), null, collector);
        var original = new AssistantRequest(tenantId, conversationId,
                "¿Ese descuento corresponde a la promoción de la XLT que acabamos de revisar?",
                "BOT_ACTIVE", "existing memory", false,
                new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of("respuesta previa"), "repair", collector);
        var context = new ToolContext(original.tenantId(), original.conversationId(), Set.of("list_promotions"));
        prefetchPort.complete(firstBuyerTurn, new ToolContext(tenantId, conversationId));
        var response = prefetchPort.complete(original, context);

        assertThat(response.content()).isEqualTo("respuesta Qwen");
        assertThat(received.get().memoryContext()).startsWith("existing memory")
                .contains("[REPLAY PREFETCHED PROMOTION EVIDENCE]",
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
        verify(tools).listPromotions("RANGER", 2027, null, false, 0, 20, "full", "RANGER 2027");
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

    private static AssistantRequest request(UUID tenantId, UUID conversationId, String message,
                                            DecisionTraceCollector traceCollector) {
        return new AssistantRequest(tenantId, conversationId, message, "BOT_ACTIVE",
                "existing memory", false,
                new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of(), null, traceCollector);
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
