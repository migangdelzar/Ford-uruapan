package com.ford.service.ai.application;

import java.util.List;

public interface ModelProviderPolicy {
    ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements);

    default List<ProviderDescriptor> candidates(AiExecutionContext context, ModelRequirements requirements) {
        return List.of(select(context, requirements));
    }

    default List<ProviderDescriptor> providers() {
        return List.of();
    }
}
