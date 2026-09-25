package com.ford.service.leads.adapter.in.web;

import com.ford.service.leads.api.LeadPage;
import com.ford.service.leads.api.LeadUpdateRequest;
import com.ford.service.leads.api.LeadView;
import com.ford.service.leads.application.LeadCommandService;
import com.ford.service.leads.application.LeadQueryService;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {
    private final LeadQueryService leads;
    private final LeadCommandService commands;

    public LeadController(LeadQueryService leads, LeadCommandService commands) {
        this.leads = leads;
        this.commands = commands;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_leads:read') || hasRole('ADMIN')")
    public LeadPage list(@RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "25") int pageSize,
                         @RequestParam(required = false) String temperature,
                         @RequestParam(required = false) UUID tenantId,
                         Authentication authentication) {
        return leads.list(tenant(authentication, tenantId), cursor, pageSize, temperature);
    }

    @GetMapping("/{leadId}")
    @PreAuthorize("hasAuthority('SCOPE_leads:read') || hasRole('ADMIN')")
    public ResponseEntity<LeadView> find(@PathVariable UUID leadId,
                                         @RequestParam(required = false) UUID tenantId,
                                         Authentication authentication) {
        return leads.find(tenant(authentication, tenantId), leadId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{leadId}")
    @PreAuthorize("hasAuthority('SCOPE_leads:write') || hasRole('ADMIN')")
    public ResponseEntity<LeadView> update(@PathVariable UUID leadId,
                                           @RequestParam(required = false) UUID tenantId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestHeader("If-Match") String ifMatch,
                                           @RequestBody LeadUpdateRequest request,
                                           Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = tenant(authentication, tenantId);
        return ResponseEntity.ok(commands.update(operator, selectedTenant, leadId,
                parseVersion(ifMatch), idempotencyKey, request));
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw new IllegalArgumentException("If-Match is required");
        var value = ifMatch.trim();
        if (value.startsWith("\"v") && value.endsWith("\"")) value = value.substring(2, value.length() - 1);
        else if (value.startsWith("v")) value = value.substring(1);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a conversation version", exception);
        }
    }

    private static UUID tenant(Authentication authentication, UUID requested) {
        var operator = AuthenticatedOperator.from(authentication);
        if (requested != null) {
            if (!operator.canAccess(requested)) throw new SecurityException("operator is not authorized for tenant");
            return requested;
        }
        if (operator.tenantIds().size() != 1) throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        return operator.tenantIds().iterator().next();
    }
}
