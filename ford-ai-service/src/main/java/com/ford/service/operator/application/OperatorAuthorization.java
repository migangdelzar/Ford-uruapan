package com.ford.service.operator.application;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("operatorAuthorization")
public class OperatorAuthorization {

    public boolean canAccess(Authentication authentication, UUID tenantId) {
        return AuthenticatedOperator.from(authentication).canAccess(tenantId);
    }

    public AuthenticatedOperator operator(Authentication authentication) {
        return AuthenticatedOperator.from(authentication);
    }
}
