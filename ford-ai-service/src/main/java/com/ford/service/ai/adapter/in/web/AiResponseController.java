package com.ford.service.ai.adapter.in.web;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.operator.application.AuthenticatedOperator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/respond")
public class AiResponseController {

    private final AssistantService assistant;

    public AiResponseController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public AiRespondResponse respond(@Valid @RequestBody AiRespondRequest request,
                                     Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var conversationId = request.conversationId() == null
                ? UUID.randomUUID() : request.conversationId();
        var latest = request.messages().get(request.messages().size() - 1);
        if (!"user".equalsIgnoreCase(latest.role()) && !"customer".equalsIgnoreCase(latest.role())) {
            throw new IllegalArgumentException("the latest message must be from the user");
        }
        var memory = request.messages().size() == 1 ? null : memoryPrompt(request.messages());
        AssistantTurn turn = assistant.respond(new AiExecutionContext(
                operator.tenantIds().iterator().next(),
                Boolean.TRUE.equals(request.containsSensitiveData()),
                conversationId, memory), latest.content());
        var decision = turn.decision();
        return new AiRespondResponse(turn.text(),
                decision.leadTemperature(), turn.requestsHuman(), handoffReason(decision),
                turn.intent().name(), decision.leadStage().name(), decision.toolFamily().name(),
                turn.sourceIds());
    }

    private static String memoryPrompt(List<AiMessage> messages) {
        var builder = new StringBuilder("Historial reciente:");
        messages.subList(0, messages.size() - 1).forEach(message -> builder
                .append("\n- ").append(message.role()).append(": ").append(message.content()));
        return builder.toString();
    }

    private static String handoffReason(ConversationDecision decision) {
        return decision.handoffRecommendation().name();
    }

    public record AiRespondRequest(
            UUID conversationId,
            @NotEmpty List<@Valid AiMessage> messages,
            Boolean containsSensitiveData) {
    }

    public record AiMessage(
            @NotBlank String role,
            @NotBlank String content) {
    }

    public record AiRespondResponse(
            String replyText,
            LeadTemperature suggestedTemperature,
            boolean handoffSuggested,
            String handoffReason,
            String intent,
            String leadStage,
            String toolFamily,
            List<String> sourceDocumentIds) {

        public AiRespondResponse {
            sourceDocumentIds = List.copyOf(sourceDocumentIds == null ? List.of() : sourceDocumentIds);
        }
    }
}
