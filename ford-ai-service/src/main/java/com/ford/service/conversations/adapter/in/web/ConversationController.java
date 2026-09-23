package com.ford.service.conversations.adapter.in.web;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationFilter;
import com.ford.service.conversations.api.ConversationMessagePage;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.ConversationView;
import com.ford.service.conversations.application.port.in.ConversationQuery;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationQuery query;

    public ConversationController(ConversationQuery query) {
        this.query = query;
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
            Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return query.search(new ConversationFilter(operator.tenantIds().iterator().next(), cursor, pageSize,
                state, channel, search, assignedOperatorId));
    }

    @GetMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ResponseEntity<ConversationView> get(@PathVariable UUID conversationId,
                                                Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return operator.tenantIds().stream()
                .map(tenantId -> query.find(tenantId, conversationId))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .map(view -> ResponseEntity.ok().header(HttpHeaders.ETAG, etag(view.version())).body(view))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ResponseEntity<ConversationMessagePage> messages(@PathVariable UUID conversationId,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(defaultValue = "50") int pageSize,
                                                             Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        for (var tenantId : operator.tenantIds()) {
            if (query.find(tenantId, conversationId).isPresent()) {
                return ResponseEntity.ok(query.messages(tenantId, conversationId, cursor, pageSize));
            }
        }
        return ResponseEntity.notFound().build();
    }

    private static String etag(long version) {
        return "\"v" + version + "\"";
    }
}
