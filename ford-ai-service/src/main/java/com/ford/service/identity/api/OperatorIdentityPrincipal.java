package com.ford.service.identity.api;

import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated principal carrying the server-loaded operator identity. */
public interface OperatorIdentityPrincipal extends UserDetails {

    OperatorIdentity identity();
}
