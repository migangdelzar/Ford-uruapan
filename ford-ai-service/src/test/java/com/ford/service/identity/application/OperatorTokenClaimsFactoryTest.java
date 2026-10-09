package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorTokenClaimsFactoryTest {

    @Test
    void signsOnlyRegistryRolesTenantsAndTheIntersectionOfRequestedScopes() {
        var tenant = UUID.randomUUID();
        var identity = new OperatorIdentity(UUID.randomUUID(), "operator@example.com", "Operator", true,
                Set.of("OPERATOR"), Set.of(tenant), Set.of("operator:read", "leads:read"));

        var claims = new OperatorTokenClaimsFactory().claims(identity,
                Set.of("operator:read", "admin:*", "leads:write"));

        assertThat(claims).containsEntry("sub", identity.subject())
                .containsEntry("roles", Set.of("OPERATOR"))
                .containsEntry("tenant_ids", java.util.List.of(tenant.toString()))
                .containsEntry("scope", "operator:read");
    }

    @Test
    void omitsProfileClaimsWhenMatchingScopesWereNotGranted() {
        var identity = new OperatorIdentity(UUID.randomUUID(), "operator@example.com", "Operator", true,
                Set.of("OPERATOR"), Set.of(UUID.randomUUID()), Set.of("operator:read"));

        var claims = new OperatorTokenClaimsFactory().claims(identity, Set.of("operator:read"));

        assertThat(claims).doesNotContainKeys("email", "name");
    }
}
