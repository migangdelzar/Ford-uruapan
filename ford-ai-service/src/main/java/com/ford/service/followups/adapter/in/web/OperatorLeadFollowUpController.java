package com.ford.service.followups.adapter.in.web;

import com.ford.service.followups.application.LeadFollowUpService;
import com.ford.service.followups.domain.LeadFollowUp;
import com.ford.service.handoff.api.OperatorPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/follow-ups")
public class OperatorLeadFollowUpController {
    private final LeadFollowUpService service;

    public OperatorLeadFollowUpController(LeadFollowUpService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<LeadFollowUp> list(@RequestParam(required = false) UUID tenantId,
                                   Authentication authentication) {
        var principal = OperatorPrincipal.from(authentication);
        if (tenantId != null && !principal.canAccess(tenantId)) {
            throw new SecurityException("Operator is not authorized for tenant");
        }
        var tenants = tenantId == null ? principal.tenantIds() : java.util.Set.of(tenantId);
        return tenants.stream().flatMap(id -> service.queued(id).stream()).toList();
    }

    @PostMapping("/{followUpId}/claim")
    @PreAuthorize("hasAuthority('SCOPE_handoff:manage') || hasRole('ADMIN')")
    public void claim(@PathVariable UUID followUpId, @RequestParam UUID tenantId,
                      Authentication authentication) {
        var principal = OperatorPrincipal.from(authentication);
        if (!principal.canAccess(tenantId)) throw new SecurityException("Operator is not authorized for tenant");
        if (!service.claim(tenantId, followUpId)) throw new java.util.NoSuchElementException("follow-up not available");
    }
}
