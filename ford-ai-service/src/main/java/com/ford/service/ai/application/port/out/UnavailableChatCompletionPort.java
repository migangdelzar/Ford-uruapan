package com.ford.service.ai.application.port.out;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.ToolContext;

public final class UnavailableChatCompletionPort implements ChatCompletionPort {

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        throw new IllegalStateException("No AI provider is configured");
    }
}
