package com.ford.service.channels.adapter.in.web;

import com.ford.service.channels.application.port.in.TemplateMessageCommand;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.application.port.out.TemplateMessagePort;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
public class MetaWindowController {
    private final CustomerWindowPort windows;
    private final TemplateMessagePort templates;

    public MetaWindowController(CustomerWindowPort windows, TemplateMessagePort templates) {
        this.windows = windows;
        this.templates = templates;
    }

    @GetMapping("/window")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public WindowView window(@PathVariable UUID conversationId, @RequestParam UUID tenantId,
                             Authentication authentication) {
        requireTenant(authentication, tenantId);
        var state = windows.find(tenantId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        return new WindowView(state.open(Instant.now()), state.lastInboundAt(), state.expiresAt(), state.channel().name());
    }

    @PostMapping("/hsm")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ResponseEntity<TemplateMessagePort.TemplateReceipt> sendTemplate(
            @PathVariable UUID conversationId, @RequestParam UUID tenantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TemplateMessageCommand command, Authentication authentication) {
        requireTenant(authentication, tenantId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        return ResponseEntity.accepted().body(templates.send(tenantId, conversationId, command, idempotencyKey));
    }

    private static void requireTenant(Authentication authentication, UUID tenantId) {
        if (!AuthenticatedOperator.from(authentication).canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
    }

    public record WindowView(boolean open, Instant lastInboundAt, Instant expiresAt, String channel) { }
}
