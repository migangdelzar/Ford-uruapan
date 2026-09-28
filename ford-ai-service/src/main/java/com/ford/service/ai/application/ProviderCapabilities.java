package com.ford.service.ai.application;

public record ProviderCapabilities(boolean toolCalling, boolean structuredOutput, boolean embeddings,
                                   boolean imageInput, boolean audioInput) {
    public ProviderCapabilities(boolean toolCalling, boolean structuredOutput, boolean embeddings) {
        this(toolCalling, structuredOutput, embeddings, false, false);
    }
}
