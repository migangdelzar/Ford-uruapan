package com.ford.service.conversations;

import java.util.List;
import java.util.Map;

/** Selects evidence belonging to one synthetic replay message. */
final class LocalReplayTurnEvidence {
    private LocalReplayTurnEvidence() { }

    static List<LocalQwenTranscriptWriter.ToolTrace> forTurn(
            String providerMessageId,
            Map<String, List<LocalQwenTranscriptWriter.ToolTrace>> eventsByProviderMessageId) {
        if (providerMessageId == null || eventsByProviderMessageId == null) return List.of();
        var events = eventsByProviderMessageId.get(providerMessageId);
        return events == null ? List.of() : events;
    }
}
