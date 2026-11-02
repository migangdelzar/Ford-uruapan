package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantTurn;
import org.springframework.modulith.NamedInterface;

@NamedInterface
public interface AssistantService {
    AssistantTurn respond(AiExecutionContext context, String userText);
}
