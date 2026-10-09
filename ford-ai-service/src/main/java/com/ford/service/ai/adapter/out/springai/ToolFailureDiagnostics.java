package com.ford.service.ai.adapter.out.springai;

import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Produces bounded, content-safe tool failure labels for logs and metrics. */
final class ToolFailureDiagnostics {

    private ToolFailureDiagnostics() {
    }

    static String toolFamily(String toolName) {
        var name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("finance") || name.contains("promotion") || name.contains("offer")) {
            return "finance";
        }
        if (name.contains("vehicle") || name.contains("inventory") || name.contains("compare")) {
            return "catalog";
        }
        return "knowledge";
    }

    static Diagnostics describe(String toolName, Throwable failure) {
        var root = rootCause(failure);
        Integer status = status(failure);
        return new Diagnostics(toolFamily(toolName), category(status, root), root.getClass().getSimpleName(), status);
    }

    private static String category(Integer status, Throwable root) {
        if (status != null) {
            if (status == 429) return "rate_limited";
            if (status >= 500) return "upstream";
            if (status >= 400) return "rejected";
        }
        if (root instanceof SocketTimeoutException || root instanceof TimeoutException) return "timeout";
        if (root instanceof ConnectException || root instanceof UnknownHostException) return "network";
        return "tool_error";
    }

    private static Integer status(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) {
                return response.getStatusCode().value();
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException("unknown tool failure") : current;
    }

    record Diagnostics(String tool, String category, String rootCauseType, Integer status) {
    }
}
