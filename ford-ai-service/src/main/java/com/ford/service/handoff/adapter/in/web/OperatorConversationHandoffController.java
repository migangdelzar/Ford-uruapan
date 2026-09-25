package com.ford.service.handoff.adapter.in.web;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OperatorConversationHandoffController {

    private final HandoffService service;

    public OperatorConversationHandoffController(HandoffService service) {
        this.service = service;
    }

    @PostMapping("/conversations/{conversationId}/takeover")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket takeover(@PathVariable UUID conversationId,
                                  @RequestParam(required = false) UUID tenantId,
                                  @RequestBody(required = false) TakeoverRequest request,
                                  Authentication authentication) {
        var principal = principal(authentication);
        var selectedTenant = tenant(principal, tenantId);
        var created = service.request(new HandoffRequest(selectedTenant, conversationId, null,
                request == null || request.reason() == null || request.reason().isBlank()
                        ? "operator_takeover" : request.reason()), principal);
        return service.claim(created.id(), 0, principal);
    }

    @PostMapping("/conversations/{conversationId}/handback")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket handback(@PathVariable UUID conversationId,
                                  @RequestParam long expectedVersion,
                                  Authentication authentication) {
        return service.resumeBot(conversationId, expectedVersion, principal(authentication));
    }

    @GetMapping("/handoffs")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public HandoffPage list(@RequestParam(required = false) HandoffStatus status,
                            @RequestParam(defaultValue = "25") int pageSize,
                            Authentication authentication) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page size must be between 1 and 100");
        }
        var items = service.list(principal(authentication)).stream()
                .filter(ticket -> status == null || ticket.status() == status)
                .limit(pageSize)
                .toList();
        return new HandoffPage(items, items.size(), false);
    }

    @PostMapping("/handoffs")
    @PreAuthorize("(hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')) && @tenantAuthorization.canAccess(authentication, #request.tenantId())")
    public ResponseEntity<HandoffTicket> request(@RequestBody HandoffRequest request,
                                                  Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.request(request, principal(authentication)));
    }

    @PostMapping("/handoffs/{handoffId}/claim")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket claim(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        return service.claim(handoffId, expectedVersion, principal(authentication));
    }

    @PostMapping("/handoffs/{handoffId}/pause")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket pause(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        var ticket = service.list(principal(authentication)).stream()
                .filter(candidate -> candidate.id().equals(handoffId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("handoff not found"));
        return service.pauseBot(ticket.conversationId(), expectedVersion, principal(authentication));
    }

    @PostMapping("/handoffs/{handoffId}/resume")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket resume(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                                Authentication authentication) {
        var ticket = service.list(principal(authentication)).stream()
                .filter(candidate -> candidate.id().equals(handoffId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("handoff not found"));
        return service.resumeBot(ticket.conversationId(), expectedVersion, principal(authentication));
    }

    private static OperatorPrincipal principal(Authentication authentication) {
        return OperatorPrincipal.from(authentication);
    }

    private static UUID tenant(OperatorPrincipal principal, UUID requested) {
        if (requested != null) {
            if (!principal.canAccess(requested)) throw new SecurityException("operator is not authorized for tenant");
            return requested;
        }
        if (principal.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return principal.tenantIds().iterator().next();
    }

    public record TakeoverRequest(String reason) {
    }

    public record HandoffPage(List<HandoffTicket> items, int pageSize, boolean hasNext) {
        public HandoffPage {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }
}
