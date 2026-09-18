package com.ford.service.ai.application;

public record ProviderCapabilities(boolean toolCalling, boolean structuredOutput, boolean embeddings) {
}
