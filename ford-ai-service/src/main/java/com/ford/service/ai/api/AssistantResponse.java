package com.ford.service.ai.api;

import java.util.List;

public record AssistantResponse(
        String content,
        boolean handoffRequired,
        String provider,
        List<String> sourceDocumentIds) {
    public AssistantResponse {
        sourceDocumentIds = List.copyOf(sourceDocumentIds);
    }
}
