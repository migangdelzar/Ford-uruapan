package com.ford.service.identity.adapter.in.web;

import com.ford.service.config.AuthenticationProperties;
import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.application.OperatorIdentityService;
import com.ford.service.identity.application.OperatorUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorPasswordAuthenticationProviderTest {

    @Test
    void authenticatesConfiguredOperatorPassword() {
        var identities = mock(OperatorIdentityService.class);
        var email = "miky.delzar@gmail.com";
        var password = "test-only-password";
        when(identities.findActiveByEmail(email)).thenReturn(Optional.of(new OperatorIdentity(
                UUID.randomUUID(), email, "Miky", true,
                Set.of("ADMIN"), Set.of(UUID.randomUUID()), Set.of("operator:read"))));
        var properties = new AuthenticationProperties();
        properties.setPasswordLoginEmail(email);
        properties.setPasswordLoginPassword(password);
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var userDetails = new OperatorUserDetailsService(identities, properties, encoder);

        var provider = new OperatorAuthenticationConfiguration().operatorPasswordAuthenticationProvider(userDetails, encoder);
        var result = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));

        assertThat(result).isNotNull();
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo(email);
    }
}
