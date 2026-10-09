package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public class IdentityCheckedOneTimeTokenService implements OneTimeTokenService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private final OneTimeTokenService delegate;
    private final OperatorIdentityService identities;
    private final OneTimeTokenRequestLimiter limiter;
    private final Clock clock;

    public IdentityCheckedOneTimeTokenService(OneTimeTokenService delegate, OperatorIdentityService identities,
            OneTimeTokenRequestLimiter limiter, Clock clock) {
        this.delegate = delegate;
        this.identities = identities;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Override
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        var username = request.getUsername();
        try {
            var email = OperatorIdentity.normalizeEmail(username);
            if (limiter.allow(email) && identities.findActiveByEmail(email).isPresent()) {
                var token = delegate.generate(new GenerateOneTimeTokenRequest(email, TOKEN_TTL));
                return new OneTimeTokenResult(token.getTokenValue(), email, token.getExpiresAt(), true);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid identifiers get the same response as unknown or throttled identities.
        }
        return new OneTimeTokenResult(UUID.randomUUID().toString(), "", clock.instant().plus(TOKEN_TTL), false);
    }

    @Override
    public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        return delegate.consume(authenticationToken);
    }
}
