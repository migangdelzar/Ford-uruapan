package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.GroundedSourceLinkRenderer;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SpringAiChatCompletionAdapter implements ProviderChatCompletionPort {

    private final ChatClient client;
    private final String provider;
    private final ToolAuthorizationPolicy toolPolicy;
    private final DecisionPolicy decisionPolicy;
    private final SystemPromptService systemPromptService;
    private final GroundedSourceLinkRenderer sourceLinkRenderer;

    public SpringAiChatCompletionAdapter(ChatClient client, String provider) {
        this(client, provider, new ToolAuthorizationPolicy(), new DecisionPolicy(), null,
                new GroundedSourceLinkRenderer());
    }

    public SpringAiChatCompletionAdapter(ChatClient client, String provider,
                                         ToolAuthorizationPolicy toolPolicy) {
        this(client, provider, toolPolicy, new DecisionPolicy(), null, new GroundedSourceLinkRenderer());
    }

    public SpringAiChatCompletionAdapter(ChatClient client, String provider,
                                         ToolAuthorizationPolicy toolPolicy,
                                         DecisionPolicy decisionPolicy) {
        this(client, provider, toolPolicy, decisionPolicy, null, new GroundedSourceLinkRenderer());
    }

    public SpringAiChatCompletionAdapter(ChatClient client, String provider,
                                         ToolAuthorizationPolicy toolPolicy,
                                         DecisionPolicy decisionPolicy,
                                         SystemPromptService systemPromptService) {
        this(client, provider, toolPolicy, decisionPolicy, systemPromptService,
                new GroundedSourceLinkRenderer());
    }

    public SpringAiChatCompletionAdapter(ChatClient client, String provider,
                                         ToolAuthorizationPolicy toolPolicy,
                                         DecisionPolicy decisionPolicy,
                                         SystemPromptService systemPromptService,
                                         GroundedSourceLinkRenderer sourceLinkRenderer) {
        this.client = Objects.requireNonNull(client);
        this.provider = Objects.requireNonNull(provider);
        this.toolPolicy = Objects.requireNonNull(toolPolicy);
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy);
        this.systemPromptService = systemPromptService;
        this.sourceLinkRenderer = Objects.requireNonNull(sourceLinkRenderer);
    }

    @Override
    public String providerId() {
        return provider;
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        var prompt = client.prompt()
                .system(enforceGroundedAnswers(systemPromptService == null
                        ? systemPrompt(request.memoryContext(), request.decision())
                        : systemPromptService.currentContent()))
                .user(request.message());
        var executionContext = new AiExecutionContext(
                request.tenantId(), request.containsSensitiveData(), request.conversationId(),
                request.memoryContext(), request.decision());
        var tools = request.decision().evidence().isEmpty()
                ? toolPolicy.readOnlyTools(executionContext)
                : toolPolicy.readOnlyTools(executionContext, decisionPolicy.allowedToolNames(
                        request.decision(), toolPolicy.availableReadOnlyToolNames()));
        var toolOutputs = new CopyOnWriteArrayList<String>();
        if (!tools.isEmpty()) {
            prompt = prompt.tools(tools.stream()
                    .map(tool -> new CapturingToolCallback(tool, toolOutputs))
                    .toArray());
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
        return new AssistantResponse(sourceLinkRenderer.render(content, toolOutputs), false, provider, List.of());
    }

    private static String systemPrompt(String memory, com.ford.service.ai.application.ConversationDecision decision) {
        var prompt = new StringBuilder("Responde en español, no inventes datos y usa solo herramientas autorizadas.");
        if (decision != null && !decision.evidence().isEmpty()) {
            prompt.append("\n\n").append(decision.toPromptContext());
        }
        if (memory == null || memory.isBlank()) {
            return prompt.toString();
        }
        return prompt.append("\n\n[MEMORIA DE SESIÓN]\n")
                .append(safe(memory)).append("\n[/MEMORIA DE SESIÓN]").toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("[MEMORIA DE SESIÓN]", "[memoria]")
                .replace("[/MEMORIA DE SESIÓN]", "[/memoria]")
                .replace("[DECISION CONTEXT]", "[decision]")
                .replace("[/DECISION CONTEXT]", "[/decision]");
    }

    private static String enforceGroundedAnswers(String configuredPrompt) {
        return configuredPrompt + "\n\n[REGLAS DE RESPUESTA CON EVIDENCIA]\n"
                + "Usa solo las herramientas disponibles en este turno. Basa precios, fechas, términos, "
                + "especificaciones y comparaciones únicamente en sus resultados. Copia enlaces oficiales "
                + "devueltos por las herramientas; nunca construyas rutas ni parámetros. Si falta evidencia, "
                + "di qué dato no está confirmado y ofrece verificarlo.\n[/REGLAS DE RESPUESTA CON EVIDENCIA]";
    }
}
