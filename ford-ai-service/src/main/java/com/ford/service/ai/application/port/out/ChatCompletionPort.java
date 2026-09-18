package com.ford.service.ai.application.port.out;

import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;

public interface ChatCompletionPort {
    AssistantResponse complete(AssistantRequest request, ToolContext context);
}
