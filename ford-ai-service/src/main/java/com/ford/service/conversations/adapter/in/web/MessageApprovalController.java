package com.ford.service.conversations.adapter.in.web;

import com.ford.service.conversations.api.MessageApprovalActionRequest;
import com.ford.service.conversations.api.MessageApprovalActionResult;
import com.ford.service.conversations.api.MessageApprovalView;
import com.ford.service.conversations.application.MessageApprovalService;
import com.ford.service.operator.application.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/message-approvals")
public class MessageApprovalController {
    private final ObjectProvider<MessageApprovalService> approvals;

    public MessageApprovalController(ObjectProvider<MessageApprovalService> approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ApprovalPage list(@PathVariable UUID conversationId,
                             @RequestParam(required = false) UUID tenantId,
                             Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return new ApprovalPage(service().list(operator, resolveTenant(operator, tenantId), conversationId));
    }

    @PostMapping("/{approvalId}/actions")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ResponseEntity<MessageApprovalActionResult> act(
            @PathVariable UUID conversationId,
            @PathVariable UUID approvalId,
            @RequestParam(required = false) UUID tenantId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MessageApprovalActionRequest request,
            Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var receipt = service().act(operator, resolveTenant(operator, tenantId), conversationId, approvalId,
                parseVersion(ifMatch), idempotencyKey, request);
        return ResponseEntity.ok().header(org.springframework.http.HttpHeaders.ETAG,
                etag(receipt.conversationVersion())).body(receipt);
    }

    private static UUID resolveTenant(AuthenticatedOperator operator, UUID requestedTenant) {
        if (requestedTenant != null) {
            if (!operator.canAccess(requestedTenant)) throw new SecurityException("operator is not authorized for tenant");
            return requestedTenant;
        }
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator.tenantIds().iterator().next();
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw new IllegalArgumentException("If-Match is required");
        var value = ifMatch.trim();
        if (value.startsWith("\"v") && value.endsWith("\"")) value = value.substring(2, value.length() - 1);
        else if (value.startsWith("v")) value = value.substring(1);
        try {
            var version = Long.parseLong(value);
            if (version < 0) throw new NumberFormatException("negative version");
            return version;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("If-Match must contain a non-negative conversation version", invalid);
        }
    }

    private static String etag(long version) { return "\"v" + version + "\""; }

    private MessageApprovalService service() {
        var service = approvals.getIfAvailable();
        if (service == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Message approval is not enabled");
        return service;
    }

    public record ApprovalPage(List<MessageApprovalView> items) {
        public ApprovalPage { items = List.copyOf(items); }
    }
}
