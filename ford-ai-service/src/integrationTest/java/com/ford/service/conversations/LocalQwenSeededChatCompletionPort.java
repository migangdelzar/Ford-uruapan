package com.ford.service.conversations;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Replays a validated transcript prefix while preserving the real webhook/prefetch path. */
final class LocalQwenSeededChatCompletionPort implements ChatCompletionPort {
    static final String MEMORY_COMPACTION_SKIPPED = "Compaction skipped in seeded diagnostic.";

    private final ChatCompletionPort liveDelegate;
    private final List<LocalQwenTranscriptSeedReader.SeedReply> replies;
    private final List<NaturalBuyerConversationScenario.Turn> scenarioTurns;
    private final int seedThroughTurn;
    private final Set<Integer> forcedLiveTurns;
    private final ConcurrentHashMap<UUID, AtomicInteger> nextByConversation = new ConcurrentHashMap<>();

    LocalQwenSeededChatCompletionPort(ChatCompletionPort liveDelegate,
                                      List<LocalQwenTranscriptSeedReader.SeedReply> replies,
                                      int seedThroughTurn) {
        this(liveDelegate, replies, replies.stream().map(reply ->
                new NaturalBuyerConversationScenario.Turn(reply.turnNumber(), reply.buyerMessage(),
                        NaturalBuyerConversationScenario.Expectation.SOCIAL)).toList(), seedThroughTurn, Set.of());
    }

    LocalQwenSeededChatCompletionPort(ChatCompletionPort liveDelegate,
                                      List<LocalQwenTranscriptSeedReader.SeedReply> replies,
                                      int seedThroughTurn, Set<Integer> forcedLiveTurns) {
        this(liveDelegate, replies, replies.stream().map(reply ->
                new NaturalBuyerConversationScenario.Turn(reply.turnNumber(), reply.buyerMessage(),
                        NaturalBuyerConversationScenario.Expectation.SOCIAL)).toList(), seedThroughTurn, forcedLiveTurns);
    }

    LocalQwenSeededChatCompletionPort(ChatCompletionPort liveDelegate,
                                      List<LocalQwenTranscriptSeedReader.SeedReply> replies,
                                      List<NaturalBuyerConversationScenario.Turn> scenarioTurns,
                                      int seedThroughTurn, Set<Integer> forcedLiveTurns) {
        this.liveDelegate = Objects.requireNonNull(liveDelegate, "live delegate is required");
        this.replies = List.copyOf(replies == null ? List.of() : replies);
        this.scenarioTurns = List.copyOf(scenarioTurns == null ? List.of() : scenarioTurns);
        if (seedThroughTurn < 0 || seedThroughTurn > this.replies.size()
                || seedThroughTurn > this.scenarioTurns.size()) {
            throw new IllegalArgumentException("seed prefix length must match available transcript replies");
        }
        for (var index = 0; index < seedThroughTurn; index++) {
            if (this.replies.get(index).turnNumber() != index + 1) {
                throw new IllegalArgumentException("seed replies must be contiguous from turn 1");
            }
        }
        this.seedThroughTurn = seedThroughTurn;
        this.forcedLiveTurns = Set.copyOf(forcedLiveTurns == null ? Set.of() : forcedLiveTurns);
        if (this.forcedLiveTurns.stream().anyMatch(turn -> turn < 1 || turn > this.scenarioTurns.size())) {
            throw new IllegalArgumentException("forced-live turns must be inside the replay scenario");
        }
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        Objects.requireNonNull(request, "assistant request is required");
        if ("MEMORY_COMPACTION".equals(request.conversationState())) {
            return new AssistantResponse(MEMORY_COMPACTION_SKIPPED, false,
                    "recorded-seed", List.of());
        }
        var cursor = nextByConversation.computeIfAbsent(request.conversationId(), ignored -> new AtomicInteger());
        var currentIndex = findScenarioTurn(request.message(), cursor.get());
        if (currentIndex < 0) throw new IllegalArgumentException("seed request mismatch: buyer message not in replay scenario");
        cursor.set(currentIndex + 1);
        var turnNumber = currentIndex + 1;
        if (turnNumber > seedThroughTurn || forcedLiveTurns.contains(turnNumber)) {
            return liveDelegate.complete(request, context);
        }

        var seed = replies.get(currentIndex);
        if (!seed.buyerMessage().equals(request.message())) {
            throw new IllegalArgumentException("seed request mismatch at turn " + seed.turnNumber());
        }
        return new AssistantResponse(seed.assistantReply(), false, "recorded-seed", List.of());
    }

    private int findScenarioTurn(String message, int startIndex) {
        for (var index = Math.max(0, startIndex); index < scenarioTurns.size(); index++) {
            if (scenarioTurns.get(index).message().equals(message)) return index;
        }
        return -1;
    }

    List<LocalQwenTranscriptWriter.ToolTrace> seedToolTracesForTurn(int turnNumber, String buyerMessage) {
        if (turnNumber < 1 || turnNumber > seedThroughTurn || forcedLiveTurns.contains(turnNumber)) return List.of();
        var index = turnNumber - 1;
        var seed = replies.get(index);
        if (seed.turnNumber() != turnNumber || !seed.buyerMessage().equals(buyerMessage)) return List.of();
        return seed.toolEvents().stream().map(trace -> new LocalQwenTranscriptWriter.ToolTrace(
                trace.toolName(), trace.outcome(), trace.evidenceCount(),
                LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED)).toList();
    }

    boolean isRecordedSeedReply(AssistantRequest request) {
        if (request == null || "MEMORY_COMPACTION".equals(request.conversationState())) return false;
        var cursor = nextByConversation.get(request.conversationId());
        var currentIndex = findScenarioTurn(request.message(), cursor == null ? 0 : cursor.get());
        return currentIndex >= 0 && currentIndex + 1 <= seedThroughTurn
                && !forcedLiveTurns.contains(currentIndex + 1)
                && replies.get(currentIndex).buyerMessage().equals(request.message());
    }
}
