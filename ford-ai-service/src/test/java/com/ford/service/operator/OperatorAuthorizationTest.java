package com.ford.service.operator;

import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthorizationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void extractsSubjectAuthoritiesRolesAndMultipleTenantsFromJwt() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("operator-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", "operator:read metrics:read")
                .claim("roles", List.of("OPERATOR", "MANAGER"))
                .claim("tenant_ids", List.of(TENANT_A.toString(), TENANT_B.toString()))
                .build();
        var authentication = new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("SCOPE_operator:read"),
                new SimpleGrantedAuthority("SCOPE_metrics:read"),
                new SimpleGrantedAuthority("ROLE_OPERATOR"),
                new SimpleGrantedAuthority("ROLE_MANAGER")));

        var operator = AuthenticatedOperator.from(authentication);

        assertThat(operator.subject()).isEqualTo("operator-1");
        assertThat(operator.tenantIds()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(operator.hasAuthority("SCOPE_operator:read")).isTrue();
        assertThat(operator.hasRole("OPERATOR")).isTrue();
        assertThat(operator.canAccess(TENANT_B)).isTrue();
    }

    @Test
    void acceptsSingleTenantClaimAndRejectsMissingTenantForScopedOperator() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("operator-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_id", TENANT_A.toString())
                .build();
        var authentication = new JwtAuthenticationToken(jwt, List.of());

        assertThat(AuthenticatedOperator.from(authentication).tenantIds()).containsExactly(TENANT_A);
        var withoutTenant = new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("operator-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build(), List.of());
        assertThatThrownBy(() -> AuthenticatedOperator.from(withoutTenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }
}
