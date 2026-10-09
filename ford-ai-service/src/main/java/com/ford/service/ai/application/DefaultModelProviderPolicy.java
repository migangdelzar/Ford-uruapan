package com.ford.service.ai.application;

import java.util.List;
import java.util.Objects;

public final class DefaultModelProviderPolicy implements ModelProviderPolicy {

    private final List<ProviderDescriptor> providers;

    public DefaultModelProviderPolicy(List<ProviderDescriptor> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers));
    }

    @Override
    public ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements) {
        return candidates(context, requirements).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No provider satisfies the request policy"));
    }

    @Override
    public List<ProviderDescriptor> candidates(AiExecutionContext context, ModelRequirements requirements) {
        return providers.stream()
                .filter(ProviderDescriptor::healthy)
                .filter(provider -> !requirements.toolCalling() || provider.capabilities().toolCalling())
                .filter(provider -> !requirements.structuredOutput() || provider.capabilities().structuredOutput())
                .filter(provider -> !requirements.imageInput() || provider.capabilities().imageInput())
                .filter(provider -> !requirements.audioInput() || provider.capabilities().audioInput())
                .toList();
    }

    @Override
    public List<ProviderDescriptor> providers() {
        return providers;
    }
}
