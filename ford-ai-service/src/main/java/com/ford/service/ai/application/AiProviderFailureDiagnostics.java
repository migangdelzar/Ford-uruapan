package com.ford.service.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.openai.errors.OpenAIServiceException;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Builds bounded, credential-safe diagnostics for provider failures. */
public final class AiProviderFailureDiagnostics {

    private AiProviderFailureDiagnostics() {
    }

    public static String describe(Throwable failure, boolean includeProviderDetails) {
        var serviceFailure = find(failure, OpenAIServiceException.class).orElse(null);
        Integer status = serviceFailure == null ? inferStatus(failure) : safeStatus(serviceFailure, failure);
        var category = find(failure, ProviderFollowupRejectedException.class).isPresent()
                ? ProviderFailure.PROVIDER_UNAVAILABLE
                : status != null && status == 404 ? "not_found" : failureCategory(status, failure);
        var retryAfter = serviceFailure == null ? null : safeHeader(serviceFailure, "retry-after");
        var requestId = serviceFailure == null ? null : safeHeader(serviceFailure, "x-request-id");
        var code = serviceFailure == null ? null : safeCode(serviceFailure);
        var type = serviceFailure == null ? null : safeType(serviceFailure);

        var result = new StringBuilder("category=").append(category);
        append(result, "status", status == null ? null : status.toString());
        append(result, "retryAfter", retryAfter);
        append(result, "requestId", requestId);
        append(result, "code", code);
        append(result, "type", type);
        if (includeProviderDetails) {
            append(result, "exceptionType", failure.getClass().getSimpleName());
            var mappingFailure = find(failure, JsonMappingException.class).orElse(null);
            if (mappingFailure != null) {
                append(result, "mappingPath", safePath(mappingFailure.getPathReference()));
            }
            else {
                var jacksonFailure = find(failure, JacksonException.class).orElse(null);
                if (jacksonFailure != null) {
                    append(result, "mappingPath", safePath(jacksonFailure.getPathReference()));
                }
            }
        }
        return result.toString();
    }

    public static ProviderFailure classify(Throwable failure) {
        var serviceFailure = find(failure, OpenAIServiceException.class).orElse(null);
        Integer status = serviceFailure == null ? inferStatus(failure) : safeStatus(serviceFailure, failure);
        if (find(failure, ProviderFollowupRejectedException.class).isPresent()) {
            var retryAfter = serviceFailure == null ? null
                    : parseRetryAfter(safeHeader(serviceFailure, "retry-after"));
            return new ProviderFailure(ProviderFailure.PROVIDER_UNAVAILABLE, status, retryAfter);
        }
        var category = failureCategory(status, failure);
        var retryAfter = serviceFailure == null ? null : parseRetryAfter(safeHeader(serviceFailure, "retry-after"));
        return new ProviderFailure(category, status, retryAfter);
    }

    public static RuntimeException afterReadOnlyToolResult(Throwable failure, boolean toolResultReturned) {
        if (!(failure instanceof RuntimeException runtimeFailure)) {
            return new IllegalStateException("AI provider failed", failure);
        }
        if (!toolResultReturned) {
            return runtimeFailure;
        }
        var classified = classify(runtimeFailure);
        if (classified.status() != null && classified.status() >= 400 && classified.status() < 500
                && !ProviderFailure.AUTHENTICATION.equals(classified.category())
                && !ProviderFailure.RATE_LIMITED.equals(classified.category())
                && !ProviderFailure.INVALID_RESPONSE.equals(classified.category())) {
            return new ProviderFollowupRejectedException(runtimeFailure);
        }
        return runtimeFailure;
    }

    private static <T extends Throwable> Optional<T> find(Throwable failure, Class<T> type) {
        var current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static Integer safeStatus(OpenAIServiceException failure, Throwable original) {
        try {
            return failure.statusCode();
        }
        catch (RuntimeException ignored) {
            return inferStatus(original);
        }
    }

    private static String safeHeader(OpenAIServiceException failure, String name) {
        try {
            return failure.headers().values(name).stream().findFirst().orElse(null);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeCode(OpenAIServiceException failure) {
        try {
            return failure.code().orElse(null);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeType(OpenAIServiceException failure) {
        try {
            return failure.type().orElse(null);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer inferStatus(Throwable failure) {
        var name = failure.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("ratelimit")) {
            return 429;
        }
        if (name.contains("unauthorized")) {
            return 401;
        }
        if (name.contains("forbidden")) {
            return 403;
        }
        if (name.contains("notfound")) {
            return 404;
        }
        return null;
    }

    private static String failureCategory(Integer status, Throwable failure) {
        if (find(failure, InvalidAssistantResponseException.class).isPresent()) {
            return ProviderFailure.INVALID_RESPONSE;
        }
        if (status != null) {
            if (status == 401 || status == 403) {
                return ProviderFailure.AUTHENTICATION;
            }
            if (status == 429) {
                return ProviderFailure.RATE_LIMITED;
            }
            if (status >= 500) {
                return ProviderFailure.PROVIDER_UNAVAILABLE;
            }
            if (status >= 400) {
                return ProviderFailure.INVALID_REQUEST;
            }
        }
        if (find(failure, JsonProcessingException.class).isPresent()
                || find(failure, JacksonException.class).isPresent()) {
            return ProviderFailure.INVALID_RESPONSE;
        }
        var current = failure;
        while (current != null) {
            var name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (current instanceof java.io.IOException || name.contains("timeout")
                    || name.contains("connectexception") || name.contains("socketexception")
                    || name.contains("network")) {
                return ProviderFailure.NETWORK;
            }
            current = current.getCause();
        }
        return ProviderFailure.PROVIDER_ERROR;
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null || !value.trim().matches("\\d+")) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static void append(StringBuilder result, String name, String value) {
        if (value != null && !value.isBlank()) {
            result.append(' ').append(name).append('=').append(value);
        }
    }

    private static String safePath(String path) {
        if (path == null) {
            return null;
        }
        var normalized = path.replaceAll("[\\r\\n\\t]", " ");
        return normalized.substring(0, Math.min(normalized.length(), 160));
    }
}
