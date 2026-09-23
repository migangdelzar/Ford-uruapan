package com.ford.service.team.adapter.in.web;

import com.ford.service.operator.application.AuthenticatedOperator;
import com.ford.service.team.api.AdvisorView;
import com.ford.service.team.application.AdvisorQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team/advisors")
public class AdvisorController {
    private final AdvisorQueryService advisors;

    public AdvisorController(AdvisorQueryService advisors) {
        this.advisors = advisors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<AdvisorView> list(@RequestParam(required = false) UUID tenantId,
                                  Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return advisors.list(resolveTenant(operator, tenantId), operator);
    }

    private static UUID resolveTenant(AuthenticatedOperator operator, UUID requested) {
        if (requested != null) {
            if (!operator.canAccess(requested)) throw new SecurityException("operator is not authorized for tenant");
            return requested;
        }
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator.tenantIds().iterator().next();
    }
}
