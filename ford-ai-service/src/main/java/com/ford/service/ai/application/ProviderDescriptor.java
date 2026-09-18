package com.ford.service.ai.application;

public record ProviderDescriptor(
        String id,
        ProviderCapabilities capabilities,
        boolean healthy,
        boolean cloud,
        String modelVersion) {
}
