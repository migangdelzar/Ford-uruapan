package com.ford.service.ai.adapter.in.web;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.AiTestSessionStore;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.operator.application.AuthenticatedOperator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
    private final AiTestSessionStore sessions;

    public AiResponseController(AssistantService assistant, AiTestSessionStore sessions) {
        this.assistant = assistant;
        this.sessions = sessions;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public AiRespondResponse respond(@Valid @RequestBody AiRespondRequest request,
                                     Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var conversationId = request.conversationId() == null
                ? UUID.randomUUID() : request.conversationId();
        var tenantId = request.tenantId();
        if (tenantId == null) {
            if (operator.tenantIds().size() != 1) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "tenantId is required for operators with multiple tenants");
            }
            tenantId = operator.tenantIds().iterator().next();
        }
        if (!operator.canAccess(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "tenantId is not assigned to this operator");
        }
        var latest = request.messages().get(request.messages().size() - 1);
        if (!"user".equalsIgnoreCase(latest.role()) && !"customer".equalsIgnoreCase(latest.role())) {
            throw new IllegalArgumentException("the latest message must be from the user");
        }
        var storedMessages = sessions.recentMessages(tenantId, operator.subject(), conversationId, 40);
        var memory = memoryPrompt(storedMessages);
        AssistantTurn turn = assistant.respond(new AiExecutionContext(
                tenantId,
                Boolean.TRUE.equals(request.containsSensitiveData()),
                conversationId, memory), latest.content());
        sessions.saveTurn(tenantId, operator.subject(), conversationId, latest.content(), turn.text());
        var decision = turn.decision();
        return new AiRespondResponse(conversationId, turn.text(),
                decision.leadTemperature(), turn.requestsHuman(), handoffReason(decision),
                turn.intent().name(), decision.leadStage().name(), decision.toolFamily().name(),
                turn.sourceIds());
    }

    private static String memoryPrompt(List<AiTestSessionStore.StoredMessage> messages) {
        if (messages.isEmpty()) return null;
        var builder = new StringBuilder("Historial reciente:");
        messages.forEach(message -> builder
                .append("\n- ").append(message.role()).append(": ").append(message.content()));
        if (builder.length() <= 8_000) return builder.toString();
        return "Historial reciente (recortado):\n" + builder.substring(builder.length() - 7_900);
    }

    private static String handoffReason(ConversationDecision decision) {
        return decision.handoffRecommendation().name();
    }

    public record AiRespondRequest(
            UUID conversationId,
            UUID tenantId,
            @NotEmpty @Size(max = 50) List<@Valid AiMessage> messages,
            Boolean containsSensitiveData) {
    }

    public record AiMessage(
            @NotBlank String role,
            @NotBlank @Size(max = 10_000) String content) {
    }

    public record AiRespondResponse(
            UUID conversationId,
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
