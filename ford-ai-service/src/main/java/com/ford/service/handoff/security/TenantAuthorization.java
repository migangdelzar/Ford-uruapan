package com.ford.service.handoff.security;

import com.ford.service.handoff.api.OperatorPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Exposes tenant-attribute checks to Spring method security expressions.
 *
 * <p>Roles and scopes answer "what may this identity do?". This bean answers
 * "for which tenant may it do it?" without turning tenant identifiers into
 * global permissions.</p>
 */
@Component("tenantAuthorization")
public class TenantAuthorization {

    public boolean canAccess(Authentication authentication, UUID tenantId) {
        try {
            return OperatorPrincipal.from(authentication).canAccess(tenantId);
        } catch (SecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}
