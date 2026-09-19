package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SpringAiChatCompletionAdapter implements ProviderChatCompletionPort {

    private final ChatClient client;
    private final String provider;
    private final ToolAuthorizationPolicy toolPolicy;

    public SpringAiChatCompletionAdapter(ChatClient client, String provider) {
        this(client, provider, new ToolAuthorizationPolicy());
    }

    public SpringAiChatCompletionAdapter(ChatClient client, String provider,
                                         ToolAuthorizationPolicy toolPolicy) {
        this.client = Objects.requireNonNull(client);
        this.provider = Objects.requireNonNull(provider);
        this.toolPolicy = Objects.requireNonNull(toolPolicy);
    }

    @Override
    public String providerId() {
        return provider;
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        var prompt = client.prompt()
                .system("Responde en español, no inventes datos y usa solo herramientas autorizadas.")
                .user(request.message());
        var tools = toolPolicy.readOnlyTools(new AiExecutionContext(
                request.tenantId(), request.containsSensitiveData(), request.conversationId()));
        if (!tools.isEmpty()) {
            prompt = prompt.tools(tools.toArray());
        }
        if (context != null && context.tenantId() != null && context.conversationId() != null) {
            prompt = prompt.toolContext(Map.of(
                    "tenantId", context.tenantId().toString(),
                    "conversationId", context.conversationId().toString(),
                    "locale", "es-MX"));
        }
        var content = prompt.call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        return new AssistantResponse(content.strip(), false, provider, List.of());
    }
}
