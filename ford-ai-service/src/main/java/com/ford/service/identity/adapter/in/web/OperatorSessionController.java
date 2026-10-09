package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorSessionResponse;
import com.ford.service.identity.application.OperatorIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public OperatorSessionResponse current(@AuthenticationPrincipal Jwt jwt) {
        UUID operatorId;
        try {
            operatorId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var identity = identities.findActiveById(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        return OperatorSessionResponse.from(identity);
    }
}
