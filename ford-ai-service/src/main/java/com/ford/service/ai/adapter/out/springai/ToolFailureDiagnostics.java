package com.ford.service.ai.adapter.out.springai;

import org.springframework.web.client.RestClientResponseException;
import com.ford.service.ai.api.AssistantTurnUnavailableException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Produces bounded, content-free diagnostics for read-only tool failures. */
public final class ToolFailureDiagnostics {

    private static final Duration DEFAULT_IMMEDIATE_RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration MAX_IMMEDIATE_RETRY_DELAY = Duration.ofSeconds(1);

    private ToolFailureDiagnostics() {
    }

    public static Diagnostic describe(String toolName, Throwable failure) {
        var root = rootCause(failure);
        Integer status = findStatus(failure);
        String category;
        if (root instanceof SocketTimeoutException
                || root.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
            category = "timeout";
        } else if (status != null) {
            category = status == 401 || status == 403 ? "authentication"
                    : status == 429 ? "rate_limited"
                    : status >= 500 ? "source_unavailable"
                    : status >= 400 ? "invalid_request" : "tool_error";
        } else if (root instanceof ConnectException || root instanceof UnknownHostException) {
            category = "network";
        } else {
            category = "tool_error";
        }
        return new Diagnostic(toolFamily(toolName), category, root.getClass().getSimpleName(), status);
    }

    static boolean retryable(Diagnostic diagnostic) {
        return Set.of("timeout", "network", "rate_limited", "source_unavailable")
                .contains(diagnostic.category());
    }

    static boolean canRetryImmediately(Diagnostic diagnostic, Throwable failure) {
        if (!retryable(diagnostic)) return false;
        var retryAfter = retryAfter(failure);
        return retryAfter == null || retryAfter.compareTo(MAX_IMMEDIATE_RETRY_DELAY) <= 0;
    }

    static Duration immediateRetryDelay(Throwable failure) {
        var retryAfter = retryAfter(failure);
        return retryAfter == null ? DEFAULT_IMMEDIATE_RETRY_DELAY : retryAfter;
    }

    static AssistantTurnUnavailableException.Disposition disposition(Diagnostic diagnostic) {
        return retryable(diagnostic) ? AssistantTurnUnavailableException.Disposition.RETRY
                : AssistantTurnUnavailableException.Disposition.REVIEW;
    }

    private static Duration retryAfter(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof RestClientResponseException response) {
                var value = response.getResponseHeaders().getFirst("Retry-After");
                if (value != null) {
                    var normalized = value.strip();
                    if (normalized.matches("\\d+")) {
                        try {
                            return Duration.ofSeconds(Long.parseLong(normalized));
                        } catch (NumberFormatException ignored) {
                            return MAX_IMMEDIATE_RETRY_DELAY.plusMillis(1);
                        }
                    }
                    try {
                        var retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant();
                        var delay = Duration.between(Instant.now(), retryAt);
                        return delay.isNegative() ? Duration.ZERO : delay;
                    } catch (DateTimeParseException ignored) {
                        // An invalid header does not authorize a longer blocking wait.
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var current = failure;
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static Integer findStatus(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof RestClientResponseException response) {
                return response.getStatusCode().value();
            }
            current = current.getCause();
        }
        return null;
    }

    static String toolFamily(String toolName) {
        var name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (Set.of("search_vehicles", "search_vehicle_and_offers", "get_vehicle", "compare_vehicles",
                "get_vehicle_provenance", "get_catalog_status", "get_page_links", "compare_trims",
                "compare_model_years", "get_variant_comparisons").contains(name)) {
            return "catalog";
        }
        if (Set.of("search_offers", "list_promotions").contains(name)) {
            return "finance";
        }
        if (name.contains("knowledge")) {
            return "knowledge";
        }
        return "other";
    }

    public record Diagnostic(String tool, String category, String rootCauseType, Integer status) {
    }
}
