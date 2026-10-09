package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorUserDetailsServiceTest {

    private final OperatorIdentityService identities = mock(OperatorIdentityService.class);
    private final OperatorUserDetailsService service = new OperatorUserDetailsService(identities);

    @Test
    void loadsEnabledOperatorWithRegistryRolesAndNoPasswordCredential() {
        var id = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        when(identities.findActiveByEmail(" Seller@Example.com ")).thenReturn(Optional.of(
                new OperatorIdentity(id, "seller@example.com", "Seller", true,
                        Set.of("OPERATOR", "MANAGER"), Set.of(tenant), Set.of("operator:read"))));

        var details = service.loadUserByUsername(" Seller@Example.com ");

        assertThat(details.getUsername()).isEqualTo("seller@example.com");
        assertThat(details.getPassword()).isNull();
        assertThat(details.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_MANAGER", "SCOPE_operator:read");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void rejectsUnknownOperatorWithoutDisclosingWhetherEmailExists() {
        when(identities.findActiveByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Operator credentials are invalid");
    }
}
