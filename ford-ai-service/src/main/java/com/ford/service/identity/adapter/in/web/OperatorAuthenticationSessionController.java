package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorIdentityPrincipal;
import com.ford.service.identity.api.OperatorAuthenticationSessionResponse;
import com.ford.service.identity.application.OperatorIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "ford.security.auth", name = "enabled", havingValue = "true")
public class OperatorAuthenticationSessionController {

    private final OperatorIdentityService identities;
    private final PublicKeyCredentialUserEntityRepository webAuthnUsers;
    private final UserCredentialRepository credentials;

    public OperatorAuthenticationSessionController(
            OperatorIdentityService identities,
            PublicKeyCredentialUserEntityRepository webAuthnUsers,
            UserCredentialRepository credentials) {
        this.identities = identities;
        this.webAuthnUsers = webAuthnUsers;
        this.credentials = credentials;
    }

    @GetMapping("/api/auth/me")
    public OperatorAuthenticationSessionResponse current(Authentication authentication) {
        UUID operatorId;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            operatorId = parseOperatorId(jwt.getToken().getSubject());
        } else if (authentication.getPrincipal() instanceof OperatorIdentityPrincipal principal) {
            operatorId = principal.identity().id();
        } else {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var identity = identities.findActiveById(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return OperatorAuthenticationSessionResponse.from(identity, hasPasskey(identity.email()));
    }

    private boolean hasPasskey(String username) {
        var user = webAuthnUsers.findByUsername(username);
        if (user == null || user.getId() == null) return false;
        var registeredCredentials = credentials.findByUserId(user.getId());
        return registeredCredentials != null && !registeredCredentials.isEmpty();
    }

    private static UUID parseOperatorId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
