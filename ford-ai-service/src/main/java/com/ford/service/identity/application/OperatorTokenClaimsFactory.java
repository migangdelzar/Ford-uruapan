package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;

@Component
public class OperatorTokenClaimsFactory {

    public java.util.Map<String, Object> claims(OperatorIdentity identity, Set<String> requestedScopes) {
        var grantedScopes = new TreeSet<>(identity.scopes());
        grantedScopes.retainAll(requestedScopes == null ? Set.of() : requestedScopes);

        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", identity.subject());
        claims.put("roles", new TreeSet<>(identity.roles()));
        claims.put("tenant_ids", identity.tenantIds().stream().map(Object::toString).sorted().toList());
        claims.put("scope", String.join(" ", grantedScopes));
        if (grantedScopes.contains("email")) claims.put("email", identity.email());
        if (grantedScopes.contains("profile")) claims.put("name", identity.displayName());
        return java.util.Collections.unmodifiableMap(claims);
    }
}
