package com.ford.service.handoff.adapter.in.web;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.api.AfterHoursDigest;
import com.ford.service.handoff.api.HandoffTicketView;
import com.ford.service.handoff.api.AlternateContactRevealView;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.application.AfterHoursDigestService;
import com.ford.service.handoff.application.AlternateContactRevealService;
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
    private final AfterHoursDigestService afterHoursDigestService;
    private final AlternateContactRevealService alternateContactRevealService;

    public OperatorConversationHandoffController(HandoffService service,
                                                AfterHoursDigestService afterHoursDigestService,
                                                AlternateContactRevealService alternateContactRevealService) {
        this.service = service;
        this.afterHoursDigestService = afterHoursDigestService;
        this.alternateContactRevealService = alternateContactRevealService;
    }

    @PostMapping("/conversations/{conversationId}/takeover")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicketView takeover(@PathVariable UUID conversationId,
                                  @RequestParam(required = false) UUID tenantId,
                                  @RequestBody(required = false) TakeoverRequest request,
                                  Authentication authentication) {
        var principal = principal(authentication);
        var selectedTenant = tenant(principal, tenantId);
        var created = service.request(new HandoffRequest(selectedTenant, conversationId, null,
                request == null || request.reason() == null || request.reason().isBlank()
                        ? "operator_takeover" : request.reason()), principal);
        var ticket = created.status() == HandoffStatus.HUMAN_PENDING
                ? service.claim(created.id(), created.version(), principal) : created;
        return HandoffTicketView.from(ticket);
    }

    @PostMapping("/conversations/{conversationId}/handback")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicketView handback(@PathVariable UUID conversationId,
                                  @RequestParam long expectedVersion,
                                  Authentication authentication) {
        return HandoffTicketView.from(service.resumeBot(conversationId, expectedVersion, principal(authentication)));
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
                .filter(ticket -> status == null
                        ? ticket.status() != HandoffStatus.CLOSED : ticket.status() == status)
                .limit(pageSize)
                .map(HandoffTicketView::from)
                .toList();
        return new HandoffPage(items, items.size(), false);
    }

    @GetMapping("/handoffs/after-hours-digest")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public AfterHoursDigest afterHoursDigest(@RequestParam UUID tenantId, Authentication authentication) {
        return afterHoursDigestService.get(tenantId, principal(authentication));
    }

    @PostMapping("/handoffs")
    @PreAuthorize("(hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')) && @tenantAuthorization.canAccess(authentication, #request.tenantId())")
    public ResponseEntity<HandoffTicketView> request(@RequestBody HandoffRequest request,
                                                  Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(HandoffTicketView.from(service.request(request, principal(authentication))));
    }

    @PostMapping("/handoffs/{handoffId}/claim")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicketView claim(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        return HandoffTicketView.from(service.claim(handoffId, expectedVersion, principal(authentication)));
    }

    @GetMapping("/handoffs/{handoffId}/contact")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public AlternateContactRevealView revealAlternateContact(@PathVariable UUID handoffId,
                                                               Authentication authentication) {
        return alternateContactRevealService.reveal(handoffId, principal(authentication));
    }

    @PostMapping("/handoffs/{handoffId}/pause")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicketView pause(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        var ticket = service.list(principal(authentication)).stream()
                .filter(candidate -> candidate.id().equals(handoffId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("handoff not found"));
        return HandoffTicketView.from(service.pauseBot(ticket.conversationId(), expectedVersion, principal(authentication)));
    }

    @PostMapping("/handoffs/{handoffId}/resume")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicketView resume(@PathVariable UUID handoffId, @RequestParam long expectedVersion,
                                Authentication authentication) {
        var ticket = service.list(principal(authentication)).stream()
                .filter(candidate -> candidate.id().equals(handoffId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("handoff not found"));
        return HandoffTicketView.from(service.resumeBot(ticket.conversationId(), expectedVersion, principal(authentication)));
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

    public record HandoffPage(List<HandoffTicketView> items, int pageSize, boolean hasNext) {
        public HandoffPage {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }
}
