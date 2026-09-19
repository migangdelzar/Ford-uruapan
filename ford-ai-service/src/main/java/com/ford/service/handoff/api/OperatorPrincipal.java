package com.ford.service.handoff.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record OperatorPrincipal(UUID operatorId, Set<UUID> tenantIds, Set<String> authorities) {

    public OperatorPrincipal {
        if (operatorId == null || tenantIds == null || tenantIds.isEmpty() || authorities == null) {
            throw new IllegalArgumentException("Operator identity and tenant claims are required");
        }
        tenantIds = Set.copyOf(tenantIds);
        authorities = Set.copyOf(authorities);
    }

    public boolean canAccess(UUID tenantId) {
        return tenantId != null && tenantIds.contains(tenantId);
    }

    public boolean isOperator() {
        return authorities.contains("ROLE_OPERATOR") || authorities.contains("OPERATOR");
    }

    public static OperatorPrincipal from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("Authenticated operator is required");
        }
        if (authentication.getPrincipal() instanceof OperatorPrincipal principal) {
            return principal;
        }
        UUID operatorId;
        try {
            operatorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            operatorId = UUID.nameUUIDFromBytes(authentication.getName().getBytes(StandardCharsets.UTF_8));
        }
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        final Set<UUID> tenantIds;
        try {
            tenantIds = authorities.stream()
                    .filter(authority -> authority.startsWith("TENANT_"))
                    .map(authority -> authority.substring("TENANT_".length()))
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Invalid tenant claim", exception);
        }
        if (tenantIds.isEmpty()) {
            throw new SecurityException("Tenant claim is required");
        }
        return new OperatorPrincipal(operatorId, tenantIds, authorities);
    }
}
