package com.ford.service.conversations.application;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnBean(AssistantService.class)
public final class ConversationAssistantAdapter implements ConversationAssistant {

    private final AssistantService assistant;

    public ConversationAssistantAdapter(AssistantService assistant) {
        this.assistant = Objects.requireNonNull(assistant);
    }

    @Override
    public AssistantReply respond(ConversationTurnRequest request) {
        var turn = assistant.respond(new AiExecutionContext(
                request.tenantId(), false, request.conversationId()), request.message());
        return new AssistantReply(turn.text(), turn.requestsHuman());
    }
}
