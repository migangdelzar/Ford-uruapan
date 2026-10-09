package com.ford.service.ai.application;

import java.time.Duration;
import java.util.Set;

public record ProviderFailure(String category, Integer status, Duration retryAfter) {

    public static final String RATE_LIMITED = "rate_limited";
    public static final String PROVIDER_UNAVAILABLE = "provider_unavailable";
    public static final String NETWORK = "network";
    public static final String AUTHENTICATION = "authentication";
    public static final String PAYMENT_REQUIRED = "payment_required";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_RESPONSE = "invalid_response";
    public static final String PROVIDER_ERROR = "provider_error";

    private static final Set<String> CATEGORIES = Set.of(RATE_LIMITED, PROVIDER_UNAVAILABLE, NETWORK,
            AUTHENTICATION, PAYMENT_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, PROVIDER_ERROR);

    public ProviderFailure {
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Unsupported provider failure category");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-After must not be negative");
        }
    }

    public boolean retryable() {
        return RATE_LIMITED.equals(category)
                || PROVIDER_UNAVAILABLE.equals(category)
                || NETWORK.equals(category)
                || AUTHENTICATION.equals(category)
                || PAYMENT_REQUIRED.equals(category)
                || INVALID_RESPONSE.equals(category);
    }
}
