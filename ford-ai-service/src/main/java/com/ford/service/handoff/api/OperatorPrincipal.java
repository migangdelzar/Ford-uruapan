package com.ford.service.handoff.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
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
        return authorities.contains("ROLE_OPERATOR")
                || authorities.contains("ROLE_MANAGER")
                || authorities.contains("ROLE_ADMIN")
                || authorities.contains("OPERATOR")
                || authorities.contains("SCOPE_operator:read")
                || authorities.contains("SCOPE_operator:write")
                || authorities.contains("SCOPE_handoff:manage");
    }

    public static OperatorPrincipal from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("Authenticated operator is required");
        }
        if (authentication.getPrincipal() instanceof OperatorPrincipal principal) {
            return principal;
        }
        var jwt = authentication.getPrincipal() instanceof Jwt token ? token : null;
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
            tenantIds = jwt == null ? tenantIdsFromAuthorities(authorities) : tenantIdsFromJwt(jwt);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Invalid tenant claim", exception);
        }
        if (tenantIds.isEmpty()) {
            throw new SecurityException("Tenant claim is required");
        }
        return new OperatorPrincipal(operatorId, tenantIds, authorities);
    }

    private static Set<UUID> tenantIdsFromAuthorities(Set<String> authorities) {
        return authorities.stream()
                .filter(authority -> authority.startsWith("TENANT_"))
                .map(authority -> authority.substring("TENANT_".length()))
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<UUID> tenantIdsFromJwt(Jwt jwt) {
        var values = new LinkedHashSet<String>();
        addClaimValues(values, jwt.getClaims().get("tenant_ids"));
        addClaimValues(values, jwt.getClaims().get("tenant_id"));
        return values.stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    private static void addClaimValues(Collection<String> target, Object rawValue) {
        if (rawValue instanceof Collection<?> collection) {
            collection.stream().map(Object::toString).forEach(target::add);
        } else if (rawValue instanceof String string && !string.isBlank()) {
            Arrays.stream(string.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(target::add);
        }
    }
}
