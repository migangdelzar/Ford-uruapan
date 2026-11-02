package com.ford.service.conversations;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import java.util.Objects;

/** Routes every prefetched synthetic replay turn to Qwen; raw mode does not register this service. */
final class LocalReplayQwenAssistantService implements AssistantService {
    private final AssistantService qwenAssistant;

    LocalReplayQwenAssistantService(AssistantService qwenAssistant) {
        this.qwenAssistant = Objects.requireNonNull(qwenAssistant);
    }

    @Override
    public AssistantTurn respond(AiExecutionContext context, String userText) {
        return qwenAssistant.respond(context, userText);
    }
}
