package com.ford.service.handoff;

import com.ford.service.handoff.api.OperatorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorPrincipalTest {

    @Test
    void readsTenantAttributesFromJwtPrincipal() {
        var tenantId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_ids", List.of(tenantId.toString()))
                .build();
        var authentication = new UsernamePasswordAuthenticationToken(
                jwt, "credentials", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));

        var principal = OperatorPrincipal.from(authentication);

        org.assertj.core.api.Assertions.assertThat(principal.canAccess(tenantId)).isTrue();
    }

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

    @Test
    void treatsHandoffScopeAsOperatorAuthority() {
        var tenantId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "credentials",
                List.of(new SimpleGrantedAuthority("SCOPE_handoff:manage"),
                        new SimpleGrantedAuthority("TENANT_" + tenantId)));

        var principal = OperatorPrincipal.from(authentication);

        org.assertj.core.api.Assertions.assertThat(principal.isOperator()).isTrue();
    }
}
