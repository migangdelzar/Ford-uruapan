package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.api.OperatorIdentityPrincipal;
import com.ford.service.identity.application.OperatorIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorAuthenticationSessionControllerTest {

    private final OperatorIdentityService identities = mock(OperatorIdentityService.class);
    private final PublicKeyCredentialUserEntityRepository users = mock(PublicKeyCredentialUserEntityRepository.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);

    @Test
    void reportsWhetherTheAuthenticatedOperatorHasAnyRegisteredPasskey() {
        var identity = identity();
        var user = mock(PublicKeyCredentialUserEntity.class);
        when(user.getId()).thenReturn(new Bytes(new byte[]{1, 2, 3}));
        when(users.findByUsername(identity.email())).thenReturn(user);
        when(credentials.findByUserId(user.getId())).thenReturn(List.of(mock(CredentialRecord.class)));
        var controller = controller(identity);

        var response = controller.current(authentication(identity));

        assertThat(response.hasPasskey()).isTrue();
    }

    @Test
    void reportsNoPasskeyWhenWebAuthnHasNoUserEntityOrCredential() {
        var identity = identity();
        when(users.findByUsername(identity.email())).thenReturn(null);
        var controller = controller(identity);

        var response = controller.current(authentication(identity));

        assertThat(response.hasPasskey()).isFalse();
    }

    @Test
    void reportsNoPasskeyWhenTheUserEntityHasNoStoredCredentials() {
        var identity = identity();
        var user = mock(PublicKeyCredentialUserEntity.class);
        when(user.getId()).thenReturn(new Bytes(new byte[]{4, 5, 6}));
        when(users.findByUsername(identity.email())).thenReturn(user);
        when(credentials.findByUserId(user.getId())).thenReturn(List.of());

        var response = controller(identity).current(authentication(identity));

        assertThat(response.hasPasskey()).isFalse();
    }

    private OperatorAuthenticationSessionController controller(OperatorIdentity identity) {
        when(identities.findActiveById(identity.id())).thenReturn(Optional.of(identity));
        return new OperatorAuthenticationSessionController(identities, users, credentials);
    }

    private static TestingAuthenticationToken authentication(OperatorIdentity identity) {
        var principal = mock(OperatorIdentityPrincipal.class);
        when(principal.identity()).thenReturn(identity);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    private static OperatorIdentity identity() {
        return new OperatorIdentity(UUID.randomUUID(), "seller@example.com", "Seller", true,
                Set.of("ADMIN"), Set.of(UUID.randomUUID()), Set.of("operator:read"));
    }
}
