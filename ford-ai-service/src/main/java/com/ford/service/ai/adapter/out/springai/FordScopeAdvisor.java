package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.AssistantScopePolicy;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Defense-in-depth advisor for callers that reach ChatClient without the application service. */
public final class FordScopeAdvisor implements CallAdvisor {

    public static final String MEMORY_CONTEXT_PARAMETER = "ford.conversation.memoryContext";

    private final AssistantScopePolicy scopePolicy;

    public FordScopeAdvisor(AssistantScopePolicy scopePolicy) {
        this.scopePolicy = Objects.requireNonNull(scopePolicy);
    }

    @Override
    public String getName() {
        return "fordScopeGuard";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var userMessage = request.prompt().getUserMessage();
        var userText = userMessage == null ? "" : userMessage.getText();
        var memoryContext = request.context().get(MEMORY_CONTEXT_PARAMETER);
        if (scopePolicy.allows(userText, null,
                memoryContext instanceof String value ? value : null)) {
            return chain.nextCall(request);
        }
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(scopePolicy.scopeResponse())))),
                Map.of("scopeGuard", "blocked"));
    }
}
