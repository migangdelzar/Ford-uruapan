package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityCheckedOneTimeTokenServiceTest {

    private final OneTimeTokenService jdbcTokens = mock(OneTimeTokenService.class);
    private final OperatorIdentityService identities = mock(OperatorIdentityService.class);
    private final OneTimeTokenRequestLimiter limiter = mock(OneTimeTokenRequestLimiter.class);
    private final IdentityCheckedOneTimeTokenService service = new IdentityCheckedOneTimeTokenService(
            jdbcTokens, identities, limiter, Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void generatesFiveMinuteTokenOnlyForActiveRateLimitedIdentity() {
        var identity = new OperatorIdentity(UUID.randomUUID(), "operator@example.com", "Operator", true,
                Set.of("OPERATOR"), Set.of(UUID.randomUUID()), Set.of("operator:read"));
        when(identities.findActiveByEmail("operator@example.com")).thenReturn(Optional.of(identity));
        when(limiter.allow("operator@example.com")).thenReturn(true);
        var token = mock(OneTimeToken.class);
        when(token.getTokenValue()).thenReturn("stored-opaque-token");
        when(token.getExpiresAt()).thenReturn(Instant.parse("2026-10-09T12:05:00Z"));
        when(jdbcTokens.generate(any())).thenReturn(token);

        var result = (OneTimeTokenResult) service.generate(new GenerateOneTimeTokenRequest(" Operator@Example.com "));

        assertThat(result.deliveryAllowed()).isTrue();
        assertThat(result.getUsername()).isEqualTo("operator@example.com");
        verify(jdbcTokens).generate(org.mockito.ArgumentMatchers.argThat(request ->
                request.getUsername().equals("operator@example.com")
                        && request.getExpiresIn().equals(Duration.ofMinutes(5))));
    }

    @Test
    void returnsNeutralNonPersistedTokenForUnknownInvalidOrRateLimitedIdentity() {
        when(limiter.allow("missing@example.com")).thenReturn(true);
        when(identities.findActiveByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(limiter.allow("operator@example.com")).thenReturn(false);

        var unknown = (OneTimeTokenResult) service.generate(new GenerateOneTimeTokenRequest("missing@example.com"));
        var throttled = (OneTimeTokenResult) service.generate(new GenerateOneTimeTokenRequest("operator@example.com"));
        var malformed = (OneTimeTokenResult) service.generate(new GenerateOneTimeTokenRequest("bad-email"));

        assertThat(unknown.deliveryAllowed()).isFalse();
        assertThat(throttled.deliveryAllowed()).isFalse();
        assertThat(malformed.deliveryAllowed()).isFalse();
        assertThat(unknown.getTokenValue()).isNotBlank();
        assertThat(unknown.getExpiresAt()).isEqualTo(Instant.parse("2026-10-09T12:05:00Z"));
        verify(jdbcTokens, never()).generate(any());
    }

    @Test
    void delegatesOneTimeTokenConsumptionToSpringJdbcService() {
        var authentication = mock(OneTimeTokenAuthenticationToken.class);
        var token = mock(OneTimeToken.class);
        when(jdbcTokens.consume(authentication)).thenReturn(token);

        assertThat(service.consume(authentication)).isSameAs(token);
    }
}
