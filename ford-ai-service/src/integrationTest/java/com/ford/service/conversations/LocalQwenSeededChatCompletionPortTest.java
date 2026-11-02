package com.ford.service.conversations;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenSeededChatCompletionPortTest {
    @Test
    void servesRecordedPrefixThenDelegatesLiveWithoutCallingModelForSeeds() {
        var replies = List.of(
                new LocalQwenTranscriptSeedReader.SeedReply(1, "Hola.", "Hola, ¿en qué ayudo?"),
                new LocalQwenTranscriptSeedReader.SeedReply(2, "Busco una pickup.", "¿Qué presupuesto busca?"));
        var liveCalls = new AtomicInteger();
        ChatCompletionPort live = (request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("live Qwen reply", false, "ollama", List.of());
        };
        var scenario = List.of(
                new NaturalBuyerConversationScenario.Turn(1, "Hola.", NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(2, "Busco una pickup.", NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(3, "Compara Ranger 2027 XL con XLT.", NaturalBuyerConversationScenario.Expectation.SOCIAL));
        var port = new LocalQwenSeededChatCompletionPort(live, replies, scenario, 2, Set.of());
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThat(port.complete(request(tenantId, conversationId, "Hola."), context(tenantId, conversationId)).content())
                .isEqualTo("Hola, ¿en qué ayudo?");
        assertThat(port.complete(request(tenantId, conversationId, "Busco una pickup."),
                context(tenantId, conversationId)).content()).isEqualTo("¿Qué presupuesto busca?");
        assertThat(port.complete(request(tenantId, conversationId, "Compara Ranger 2027 XL con XLT."),
                context(tenantId, conversationId)).content()).isEqualTo("live Qwen reply");

        assertThat(liveCalls).hasValue(1);
    }

    @Test
    void rejectsPrefixMessageMismatchWithoutCallingLiveModel() {
        var liveCalls = new AtomicInteger();
        ChatCompletionPort live = (request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("live", false, "ollama", List.of());
        };
        var port = new LocalQwenSeededChatCompletionPort(live,
                List.of(new LocalQwenTranscriptSeedReader.SeedReply(1, "Hola.", "Hola.")), 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> port.complete(request(UUID.randomUUID(), UUID.randomUUID(), "Otra pregunta."),
                        context(UUID.randomUUID(), UUID.randomUUID())))
                .withMessageContaining("seed request mismatch");
        assertThat(liveCalls).hasValue(0);
    }

    @Test
    void delegatesExplicitlyForcedLiveTurnInsideRecordedPrefix() {
        var liveCalls = new AtomicInteger();
        ChatCompletionPort live = (request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("live turn", false, "ollama", List.of());
        };
        var replies = List.of(
                new LocalQwenTranscriptSeedReader.SeedReply(1, "Uno", "seed one"),
                new LocalQwenTranscriptSeedReader.SeedReply(2, "Dos", "not used seed two"),
                new LocalQwenTranscriptSeedReader.SeedReply(3, "Tres", "seed three"));
        var scenario = replies.stream().map(reply -> new NaturalBuyerConversationScenario.Turn(reply.turnNumber(),
                reply.buyerMessage(), NaturalBuyerConversationScenario.Expectation.SOCIAL)).toList();
        var port = new LocalQwenSeededChatCompletionPort(live, replies, scenario, 3, Set.of(2));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThat(port.complete(request(tenantId, conversationId, "Uno"), context(tenantId, conversationId)).content())
                .isEqualTo("seed one");
        assertThat(port.complete(request(tenantId, conversationId, "Dos"), context(tenantId, conversationId)).content())
                .isEqualTo("live turn");
        assertThat(port.complete(request(tenantId, conversationId, "Tres"), context(tenantId, conversationId)).content())
                .isEqualTo("seed three");
        assertThat(liveCalls).hasValue(1);
    }

    @Test
    void permitsForcingTheFirstUnseededTurnLive() {
        var replies = List.of(new LocalQwenTranscriptSeedReader.SeedReply(1, "Uno", "seed one"),
                new LocalQwenTranscriptSeedReader.SeedReply(2, "Dos", "seed two"));
        var scenario = List.of(
                new NaturalBuyerConversationScenario.Turn(1, "Uno", NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(2, "Dos", NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(3, "Tres", NaturalBuyerConversationScenario.Expectation.SOCIAL));
        var liveCalls = new AtomicInteger();
        ChatCompletionPort live = (request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("live turn", false, "ollama", List.of());
        };
        var port = new LocalQwenSeededChatCompletionPort(live, replies, scenario, 2, Set.of(3));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThat(port.complete(request(tenantId, conversationId, "Tres"), context(tenantId, conversationId)).content())
                .isEqualTo("live turn");
        assertThat(liveCalls).hasValue(1);
    }

    @Test
    void alignsToTheMatchingScenarioTurnWhenAnEarlierTurnSkippedModelCompletion() {
        var replies = List.of(
                new LocalQwenTranscriptSeedReader.SeedReply(1, "Uno", "seed one"),
                new LocalQwenTranscriptSeedReader.SeedReply(2, "Dos", "deterministic answer skipped model"),
                new LocalQwenTranscriptSeedReader.SeedReply(3, "Tres", "seed three"));
        var scenario = replies.stream().map(reply -> new NaturalBuyerConversationScenario.Turn(reply.turnNumber(),
                reply.buyerMessage(), NaturalBuyerConversationScenario.Expectation.SOCIAL)).toList();
        var port = new LocalQwenSeededChatCompletionPort(
                (request, context) -> new AssistantResponse("live", false, "ollama", List.of()), replies,
                scenario, 3, Set.of());
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThat(port.complete(request(tenantId, conversationId, "Uno"), context(tenantId, conversationId)).content())
                .isEqualTo("seed one");
        assertThat(port.complete(request(tenantId, conversationId, "Tres"), context(tenantId, conversationId)).content())
                .isEqualTo("seed three");
    }

    @Test
    void skipsLiveMemoryCompactionWithoutConsumingSeedCursor() {
        var liveCalls = new AtomicInteger();
        ChatCompletionPort live = (request, context) -> {
            liveCalls.incrementAndGet();
            return new AssistantResponse("compact summary", false, "ollama", List.of());
        };
        var replies = List.of(new LocalQwenTranscriptSeedReader.SeedReply(1, "Hola.", "Hola."));
        var port = new LocalQwenSeededChatCompletionPort(live, replies, 1);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var compaction = new AssistantRequest(tenantId, conversationId,
                "Summarize conversation history", "MEMORY_COMPACTION", false);

        assertThat(port.complete(compaction, context(tenantId, conversationId)).content())
                .isEqualTo("Compaction skipped in seeded diagnostic.");
        assertThat(port.complete(request(tenantId, conversationId, "Hola."), context(tenantId, conversationId)).content())
                .isEqualTo("Hola.");
        assertThat(liveCalls).hasValue(0);
    }

    @Test
    void exposesOnlyTheExactSeededTurnsRecordedToolTracesWithSeedProvenance() {
        var trace = new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 2,
                LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL);
        var reply = new LocalQwenTranscriptSeedReader.SeedReply(1, "Hola.", "Respuesta", List.of(trace));
        var port = new LocalQwenSeededChatCompletionPort(
                (request, context) -> new AssistantResponse("live", false, "ollama", List.of()),
                List.of(reply), 1);

        assertThat(port.seedToolTracesForTurn(1, "Hola.")).containsExactly(new LocalQwenTranscriptWriter.ToolTrace(
                "get_vehicle", "returned", 2, LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED));
        assertThat(port.seedToolTracesForTurn(1, "wrong buyer message")).isEmpty();
        assertThat(port.seedToolTracesForTurn(2, "Hola.")).isEmpty();
    }

    private static AssistantRequest request(UUID tenantId, UUID conversationId, String message) {
        return new AssistantRequest(tenantId, conversationId, message, "BOT_ACTIVE", null, false,
                new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.WARM, LeadStage.NEW,
                        HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()),
                null, List.of(), null, new DecisionTraceCollector());
    }

    private static ToolContext context(UUID tenantId, UUID conversationId) {
        return new ToolContext(tenantId, conversationId);
    }
}
