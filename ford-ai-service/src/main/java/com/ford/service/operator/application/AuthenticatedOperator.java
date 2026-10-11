package com.ford.service.operator.application;

import com.ford.service.identity.api.OperatorIdentityPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.modulith.NamedInterface;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Normalized identity used by operator application services.
 *
 * Tenant membership is read from the validated token and is never inferred
 * from a request header supplied by the browser or BFF.
 */
@NamedInterface
public record AuthenticatedOperator(
        String subject,
        Set<String> authorities,
        Set<String> roles,
        Set<UUID> tenantIds) {

    public AuthenticatedOperator {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("operator subject is required");
        }
        authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        tenantIds = Set.copyOf(tenantIds == null ? Set.of() : tenantIds);
    }

    public static AuthenticatedOperator from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("authenticated operator is required");
        }
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var roles = authorities.stream()
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring("ROLE_".length()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            var token = jwtAuthentication.getToken();
            var tenantIds = new LinkedHashSet<UUID>();
            addTenantClaim(tenantIds, token.getClaims().get("tenant_id"));
            addTenantClaim(tenantIds, token.getClaims().get("tenant_ids"));
            if (tenantIds.isEmpty()) {
                throw new IllegalArgumentException("JWT tenant claim is required");
            }
            return new AuthenticatedOperator(token.getSubject(), authorities, roles, tenantIds);
        }
        if (authentication.getPrincipal() instanceof OperatorIdentityPrincipal principal) {
            var identity = principal.identity();
            if (identity == null) {
                throw new IllegalArgumentException("operator identity is required");
            }
            return new AuthenticatedOperator(identity.subject(), authorities, roles, identity.tenantIds());
        }
        throw new IllegalArgumentException("validated JWT is required for operator API access");
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    public boolean hasRole(String role) {
        var normalized = role == null ? "" : role.replaceFirst("^ROLE_", "");
        return roles.contains(normalized);
    }

    public boolean canAccess(UUID tenantId) {
        return tenantId != null && tenantIds.contains(tenantId);
    }

    private static void addTenantClaim(Set<UUID> target, Object rawClaim) {
        if (rawClaim instanceof Collection<?> collection) {
            collection.forEach(value -> addTenantValue(target, value));
        } else {
            addTenantValue(target, rawClaim);
        }
    }

    private static void addTenantValue(Set<UUID> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String string) {
            List.of(string.split(","))
                    .stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(AuthenticatedOperator::parseTenant)
                    .forEach(target::add);
        } else {
            target.add(parseTenant(value.toString()));
        }
    }

    private static UUID parseTenant(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT tenant claim contains an invalid UUID", exception);
        }
    }
}
