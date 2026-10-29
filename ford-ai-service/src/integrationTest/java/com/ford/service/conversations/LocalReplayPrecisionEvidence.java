package com.ford.service.conversations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps bounded, test-local catalog evidence for the active replay turn only. */
final class LocalReplayPrecisionEvidence {
    private final Map<UUID, TurnEvidence> evidenceByConversation = new HashMap<>();

    synchronized void beginTurn(UUID conversationId) {
        if (conversationId != null) evidenceByConversation.put(conversationId, new TurnEvidence());
    }

    synchronized void recordComparisonStatus(UUID conversationId, ComparisonPrefetchStatus status) {
        if (conversationId == null || status == null) return;
        evidenceByConversation.computeIfAbsent(conversationId, ignored -> new TurnEvidence())
                .comparisonStatus = status;
    }

    synchronized ComparisonPrefetchStatus currentTurnComparisonStatus(UUID conversationId) {
        var turn = evidenceByConversation.get(conversationId);
        return turn == null ? ComparisonPrefetchStatus.NOT_A_COMPARISON : turn.comparisonStatus;
    }

    synchronized void record(UUID conversationId, String evidence) {
        if (conversationId == null || evidence == null || evidence.isBlank()) return;
        evidenceByConversation.computeIfAbsent(conversationId, ignored -> new TurnEvidence()).evidence.add(evidence);
    }

    synchronized void recordToolEvidence(UUID conversationId, String toolName, int evidenceCount, String evidence) {
        if (conversationId == null || toolName == null || evidenceCount <= 0 || evidence == null || evidence.isBlank()) {
            return;
        }
        var turn = evidenceByConversation.computeIfAbsent(conversationId, ignored -> new TurnEvidence());
        turn.evidence.add(evidence);
        turn.traces.add(new LocalQwenTranscriptWriter.ToolTrace(toolName, "returned", evidenceCount,
                LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
    }

    synchronized List<String> currentTurnEvidence(UUID conversationId) {
        var turn = evidenceByConversation.get(conversationId);
        return turn == null ? List.of() : List.copyOf(turn.evidence);
    }

    synchronized List<LocalQwenTranscriptWriter.ToolTrace> currentTurnToolTraces(UUID conversationId) {
        var turn = evidenceByConversation.get(conversationId);
        return turn == null ? List.of() : List.copyOf(turn.traces);
    }

    private static final class TurnEvidence {
        private final List<String> evidence = new ArrayList<>();
        private final List<LocalQwenTranscriptWriter.ToolTrace> traces = new ArrayList<>();
        private ComparisonPrefetchStatus comparisonStatus = ComparisonPrefetchStatus.NOT_A_COMPARISON;
    }

    enum ComparisonPrefetchStatus {
        NOT_A_COMPARISON,
        PAIR_UNRESOLVED,
        CATALOG_RESULT_INCOMPLETE,
        EVIDENCE_TOO_LARGE,
        INJECTED
    }
}
