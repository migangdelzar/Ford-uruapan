package com.ford.service.conversations.adapter.in.web;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationFilter;
import com.ford.service.conversations.api.ConversationMessagePage;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.ConversationView;
import com.ford.service.conversations.application.port.in.ConversationQuery;
import com.ford.service.conversations.application.ConversationCommandService;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.operator.application.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationQuery query;
    private final ConversationCommandService commands;

    public ConversationController(ConversationQuery query, ConversationCommandService commands) {
        this.query = query;
        this.commands = commands;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ConversationPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) ConversationState state,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID assignedOperatorId,
            @RequestParam(required = false) UUID tenantId,
            Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        return query.search(new ConversationFilter(selectedTenant, cursor, pageSize,
                state, channel, search, assignedOperatorId));
    }

    @GetMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ResponseEntity<ConversationView> get(@PathVariable UUID conversationId,
                                                @RequestParam(required = false) UUID tenantId,
                                                Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        return query.find(selectedTenant, conversationId)
                .map(view -> ResponseEntity.ok().header(HttpHeaders.ETAG, etag(view.version())).body(view))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ResponseEntity<ConversationMessagePage> messages(@PathVariable UUID conversationId,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(defaultValue = "50") int pageSize,
                                                             @RequestParam(required = false) UUID tenantId,
                                                             Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        if (query.find(selectedTenant, conversationId).isPresent()) {
            return ResponseEntity.ok(query.messages(selectedTenant, conversationId, cursor, pageSize));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ResponseEntity<CommandReceipt> sendMessage(
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestParam(required = false) UUID tenantId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        return ResponseEntity.accepted().body(commands.sendMessage(operator, selectedTenant, conversationId,
                parseVersion(ifMatch), idempotencyKey, request));
    }

    private static UUID resolveTenant(AuthenticatedOperator operator, UUID requestedTenant) {
        if (requestedTenant != null) {
            if (!operator.canAccess(requestedTenant)) {
                throw new SecurityException("operator is not authorized for tenant");
            }
            return requestedTenant;
        }
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator.tenantIds().iterator().next();
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required");
        }
        var value = ifMatch.trim();
        if (value.startsWith("\"v") && value.endsWith("\"")) {
            value = value.substring(2, value.length() - 1);
        } else if (value.startsWith("v")) {
            value = value.substring(1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a conversation version", exception);
        }
    }

    private static String etag(long version) {
        return "\"v" + version + "\"";
    }
}
