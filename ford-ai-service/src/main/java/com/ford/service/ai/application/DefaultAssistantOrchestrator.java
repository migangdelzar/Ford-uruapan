package com.ford.service.ai.application;

import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;

import java.util.List;
import java.util.Objects;

public final class DefaultAssistantOrchestrator implements AssistantOrchestrator {

    private final IntentRouter intentRouter;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final ChatCompletionPort completion;

    public DefaultAssistantOrchestrator(
            IntentRouter intentRouter,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            ChatCompletionPort completion) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
        this.toolAuthorizationPolicy = Objects.requireNonNull(toolAuthorizationPolicy);
        this.completion = Objects.requireNonNull(completion);
    }

    @Override
    public AssistantResponse respond(AssistantRequest request) {
        Objects.requireNonNull(request);
        if ("HUMAN_ACTIVE".equals(request.conversationState())) {
            return new AssistantResponse("Un asesor humano ya está atendiendo tu conversación.", true, "none", List.of());
        }
        var context = new AiExecutionContext(request.tenantId(), request.containsSensitiveData());
        toolAuthorizationPolicy.readOnlyTools(context);
        intentRouter.route(request.message());
        return completion.complete(request, new ToolContext(request.tenantId(), request.conversationId()));
    }
}
