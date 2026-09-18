package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;

public interface AssistantOrchestrator {
    AssistantResponse respond(AssistantRequest request);
}
