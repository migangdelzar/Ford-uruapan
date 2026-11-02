package com.ford.service.identity.api;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record OperatorIdentity(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<UUID> tenantIds,
        Set<String> scopes) implements java.io.Serializable {

    private static final Set<String> ALLOWED_ROLES = Set.of("OPERATOR", "MANAGER", "ADMIN");
    public static final Set<String> ALLOWED_SCOPES = Set.of(
            "openid", "profile", "email", "operator:read", "operator:write", "handoff:manage",
            "leads:read", "leads:write", "metrics:read", "events:read", "inventory:read",
            "finance:quote", "appointments:read", "appointments:write", "bot:manage", "testlab:run");

    public OperatorIdentity {
        if (id == null) throw new IllegalArgumentException("operator id is required");
        email = normalizeEmail(email);
        if (displayName == null || displayName.isBlank() || displayName.length() > 150) {
            throw new IllegalArgumentException("operator display name must contain 1 to 150 characters");
        }
        displayName = displayName.trim();
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        if (roles.isEmpty() || !ALLOWED_ROLES.containsAll(roles)) {
            throw new IllegalArgumentException("operator must have only supported CRM roles");
        }
        tenantIds = Set.copyOf(tenantIds == null ? Set.of() : tenantIds);
        if (tenantIds.isEmpty()) throw new IllegalArgumentException("operator must belong to a tenant");
        scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
        if (scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) {
            throw new IllegalArgumentException("operator must have only supported API scopes");
        }
    }

    public String subject() {
        return id.toString();
    }

    public static String normalizeEmail(String email) {
        if (email == null) throw new IllegalArgumentException("operator email is required");
        var normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 320 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("operator email is invalid");
        }
        return normalized;
    }

    public static String roleFromCrmRole(String role) {
        if (role == null) throw new IllegalArgumentException("operator role is required");
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "asesor", "operator" -> "OPERATOR";
            case "gerente", "manager" -> "MANAGER";
            case "propietario", "admin" -> "ADMIN";
            default -> throw new IllegalArgumentException("unsupported CRM role");
        };
    }
}
