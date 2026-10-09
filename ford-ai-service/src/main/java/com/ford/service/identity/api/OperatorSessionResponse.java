package com.ford.service.identity.api;

import java.util.List;
import java.util.UUID;

public record OperatorSessionResponse(
        UUID operatorId,
        String displayName,
        String email,
        List<String> roles,
        List<UUID> tenantIds) {

    public static OperatorSessionResponse from(OperatorIdentity identity) {
        return new OperatorSessionResponse(
                identity.id(), identity.displayName(), identity.email(),
                identity.roles().stream().sorted().toList(),
                identity.tenantIds().stream().sorted().toList());
    }
}
