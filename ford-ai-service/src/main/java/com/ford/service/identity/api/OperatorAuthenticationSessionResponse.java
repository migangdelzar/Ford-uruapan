package com.ford.service.identity.api;

import java.util.List;
import java.util.UUID;

public record OperatorAuthenticationSessionResponse(
        UUID operatorId,
        String displayName,
        String email,
        List<String> roles,
        List<UUID> tenantIds,
        boolean hasPasskey) {

    public static OperatorAuthenticationSessionResponse from(OperatorIdentity identity, boolean hasPasskey) {
        return new OperatorAuthenticationSessionResponse(
                identity.id(), identity.displayName(), identity.email(),
                identity.roles().stream().sorted().toList(),
                identity.tenantIds().stream().sorted().toList(),
                hasPasskey);
    }
}
