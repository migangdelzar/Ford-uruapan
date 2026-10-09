package com.ford.service.identity.application;

import org.springframework.security.authentication.ott.OneTimeToken;

import java.time.Instant;

public record OneTimeTokenResult(String tokenValue, String username, Instant expiresAt, boolean deliveryAllowed)
        implements OneTimeToken {

    @Override public String getTokenValue() { return tokenValue; }
    @Override public String getUsername() { return username; }
    @Override public Instant getExpiresAt() { return expiresAt; }
}
