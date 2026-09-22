package com.ford.service.ai.application;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DefaultModelProviderPolicy implements ModelProviderPolicy {

    private final List<ProviderDescriptor> providers;

    public DefaultModelProviderPolicy(List<ProviderDescriptor> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers));
    }

    @Override
    public ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements) {
        var restricted = context.containsSensitiveData()
                || context.decision().sensitiveDataLevel() == SensitiveDataLevel.CONFIRMED;
        return providers.stream()
                .filter(ProviderDescriptor::healthy)
                .filter(provider -> !restricted || !provider.cloud())
                .filter(provider -> !requirements.toolCalling() || provider.capabilities().toolCalling())
                .filter(provider -> !requirements.structuredOutput() || provider.capabilities().structuredOutput())
                .sorted(Comparator.comparing(ProviderDescriptor::cloud).reversed())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No provider satisfies the request policy"));
    }

    @Override
    public List<ProviderDescriptor> providers() {
        return providers;
    }
}
