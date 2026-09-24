package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Objects;

public final class SpringAiChatCompletionAdapter implements ChatCompletionPort {

    private final ChatClient client;
    private final String provider;
    private final SystemPromptService systemPrompt;

    public SpringAiChatCompletionAdapter(ChatClient client, String provider, SystemPromptService systemPrompt) {
        this.client = Objects.requireNonNull(client);
        this.provider = Objects.requireNonNull(provider);
        this.systemPrompt = Objects.requireNonNull(systemPrompt);
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        var content = client.prompt()
                .system(systemPrompt.currentContent())
                .user(request.message())
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        return new AssistantResponse(content.strip(), false, provider, List.of());
    }
}
