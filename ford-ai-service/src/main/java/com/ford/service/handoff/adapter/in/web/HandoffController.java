package com.ford.service.handoff.adapter.in.web;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/handoffs")
public class HandoffController {

    private final HandoffService service;

    public HandoffController(HandoffService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') && @tenantAuthorization.canAccess(authentication, #request.tenantId())")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffTicket request(@RequestBody HandoffRequest request, Authentication authentication) {
        return service.request(request, principal(authentication));
    }

    @PostMapping("/{ticketId}/claim")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket claim(@PathVariable UUID ticketId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        return service.claim(ticketId, expectedVersion, principal(authentication));
    }

    @PostMapping("/conversations/{conversationId}/pause")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket pause(@PathVariable UUID conversationId, @RequestParam long expectedVersion,
                               Authentication authentication) {
        return service.pauseBot(conversationId, expectedVersion, principal(authentication));
    }

    @PostMapping("/conversations/{conversationId}/resume")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public HandoffTicket resume(@PathVariable UUID conversationId, @RequestParam long expectedVersion,
                                Authentication authentication) {
        return service.resumeBot(conversationId, expectedVersion, principal(authentication));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<HandoffTicket> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    private static OperatorPrincipal principal(Authentication authentication) {
        return OperatorPrincipal.from(authentication);
    }
}
