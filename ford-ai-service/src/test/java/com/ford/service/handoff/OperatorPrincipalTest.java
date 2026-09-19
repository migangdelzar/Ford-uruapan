package com.ford.service.handoff;

import com.ford.service.handoff.api.OperatorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorPrincipalTest {

    @Test
    void rejectsMalformedTenantClaimsWithoutTurningThemIntoAServerError() {
        var authentication = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "credentials",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"),
                        new SimpleGrantedAuthority("TENANT_not-a-uuid")));

        assertThatThrownBy(() -> OperatorPrincipal.from(authentication))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid tenant claim");
    }

    @Test
    void rejectsAuthenticatedOperatorsWithoutTenantClaims() {
        var authentication = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "credentials",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));

        assertThatThrownBy(() -> OperatorPrincipal.from(authentication))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Tenant claim is required");
    }
}
