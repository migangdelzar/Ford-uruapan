package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorIdentityPrincipal;
import com.ford.service.identity.api.OperatorSessionResponse;
import com.ford.service.identity.application.OperatorIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class OperatorAuthenticationSessionController {

    private final OperatorIdentityService identities;

    public OperatorAuthenticationSessionController(OperatorIdentityService identities) {
        this.identities = identities;
    }

    @GetMapping("/api/auth/me")
    public OperatorSessionResponse current(Authentication authentication) {
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
        return OperatorSessionResponse.from(identity);
    }

    private static UUID parseOperatorId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
