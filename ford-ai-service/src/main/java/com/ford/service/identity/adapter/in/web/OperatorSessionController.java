package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorSessionResponse;
import com.ford.service.identity.application.OperatorIdentityService;
import com.ford.service.identity.api.OperatorIdentityPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operator")
public class OperatorSessionController {

    private final OperatorIdentityService identities;

    public OperatorSessionController(OperatorIdentityService identities) {
        this.identities = identities;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') and hasAuthority('SCOPE_profile') and hasAuthority('SCOPE_email')")
    public OperatorSessionResponse current(Authentication authentication) {
        UUID operatorId;
        try {
            if (authentication.getPrincipal() instanceof OperatorIdentityPrincipal principal) {
                operatorId = principal.identity().id();
            } else if (authentication instanceof JwtAuthenticationToken jwt) {
                operatorId = UUID.fromString(jwt.getToken().getSubject());
            } else {
                throw new IllegalArgumentException("operator identity is unavailable");
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var identity = identities.findActiveById(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return OperatorSessionResponse.from(identity);
    }
}
