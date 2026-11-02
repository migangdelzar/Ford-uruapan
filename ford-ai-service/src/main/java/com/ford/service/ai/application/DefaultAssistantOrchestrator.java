package com.ford.service.ai.application;

import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultAssistantOrchestrator implements AssistantOrchestrator {

    private final IntentRouter intentRouter;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final ChatCompletionPort completion;
    private final AiObservationService observations;

    public DefaultAssistantOrchestrator(
            IntentRouter intentRouter,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            ChatCompletionPort completion) {
        this(intentRouter, toolAuthorizationPolicy, completion,
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public DefaultAssistantOrchestrator(
            IntentRouter intentRouter,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            ChatCompletionPort completion,
            AiObservationService observations) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
        this.toolAuthorizationPolicy = Objects.requireNonNull(toolAuthorizationPolicy);
        this.completion = Objects.requireNonNull(completion);
        this.observations = Objects.requireNonNull(observations);
    }

    @Override
    public AssistantResponse respond(AssistantRequest request) {
        Objects.requireNonNull(request);
        return observations.observe("ford.ai.conversation", Map.of("outcome", "success"),
                () -> respondInternal(request));
    }

    private AssistantResponse respondInternal(AssistantRequest request) {
        if ("HUMAN_ACTIVE".equals(request.conversationState())) {
            return new AssistantResponse("Un asesor ya está atendiendo su conversación.", true, "none", List.of());
        }
        var context = new AiExecutionContext(request.tenantId(), request.containsSensitiveData(),
                request.conversationId(), request.memoryContext(), request.decision(),
                request.leadJourneyContext(), request.recentAssistantReplies(), request.repairInstruction());
        var availableTools = toolAuthorizationPolicy.availableReadOnlyToolNames();
        var requestedTools = new DecisionPolicy().allowedToolNames(request.decision(), availableTools);
        var authorizedTools = toolAuthorizationPolicy.authorizedToolSet(requestedTools);
        if (request.traceCollector() != null) {
            request.traceCollector().record(new DecisionTraceEvent("authorization",
                    authorizedTools.names().isEmpty() ? "no_read_only_tools_authorized" : "read_only_tools_authorized",
                    null, "none", null, authorizedTools.names().isEmpty() ? "skipped" : "authorized",
                    authorizedTools.names().size(), java.time.Instant.now()));
        }
        toolAuthorizationPolicy.readOnlyTools(context, authorizedTools.names());
        intentRouter.route(request.message());
        return completion.complete(request,
                new ToolContext(request.tenantId(), request.conversationId(), authorizedTools.names(),
                        request.traceCollector()));
    }
}
