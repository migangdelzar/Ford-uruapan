package com.ford.service.ai.application;

import java.util.List;

public interface ModelProviderPolicy {
    ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements);

    default List<ProviderDescriptor> providers() {
        return List.of();
    }
}
