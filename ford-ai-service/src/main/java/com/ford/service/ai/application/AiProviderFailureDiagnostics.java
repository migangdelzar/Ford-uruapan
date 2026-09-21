package com.ford.service.ai.application;

import com.openai.errors.OpenAIServiceException;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Builds bounded, credential-safe diagnostics for provider failures. */
public final class AiProviderFailureDiagnostics {

    private static final int MAX_DETAIL_LENGTH = 500;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(authorization|api[_-]?key|access[_-]?token|password|secret)\\s*[:=]\\s*[^,}\\s]+"
    );

    private AiProviderFailureDiagnostics() {
    }

    public static String describe(Throwable failure, boolean includeResponseBody) {
        var serviceFailure = find(failure, OpenAIServiceException.class).orElse(null);
        Integer status = serviceFailure == null ? inferStatus(failure) : safeStatus(serviceFailure, failure);
        var category = category(status, failure);
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
        if (includeResponseBody) {
            var detail = serviceFailure == null ? failure.getMessage() : safeBody(serviceFailure);
            append(result, "responseBody", sanitize(detail));
        }
        return result.toString();
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

    private static String safeBody(OpenAIServiceException failure) {
        try {
            return failure.body().toString();
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

    private static String category(Integer status, Throwable failure) {
        if (status != null) {
            if (status == 401 || status == 403) {
                return "authentication";
            }
            if (status == 404) {
                return "not_found";
            }
            if (status == 429) {
                return "rate_limited";
            }
            if (status >= 500) {
                return "provider_unavailable";
            }
        }
        var name = failure.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("timeout") || name.contains("io")
                ? "network" : "provider_error";
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var redacted = SECRET.matcher(value).replaceAll("$1=<redacted>");
        return redacted.length() > MAX_DETAIL_LENGTH
                ? redacted.substring(0, MAX_DETAIL_LENGTH) + "..."
                : redacted;
    }

    private static void append(StringBuilder result, String name, String value) {
        if (value != null && !value.isBlank()) {
            result.append(' ').append(name).append('=').append(value);
        }
    }
}
