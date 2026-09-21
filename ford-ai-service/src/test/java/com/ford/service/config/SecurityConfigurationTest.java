package com.ford.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    @Test
    void mapsJwtTenantClaimsToTenantAuthorities() {
        var tenantId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scope", "operator:read")
                .claim("tenant_ids", List.of(tenantId.toString()))
                .build();

        var authentication = new SecurityConfiguration().jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .contains("SCOPE_operator:read", "TENANT_" + tenantId);
    }
}
