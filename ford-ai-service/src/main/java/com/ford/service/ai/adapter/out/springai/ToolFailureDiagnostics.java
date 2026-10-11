package com.ford.service.ai.adapter.out.springai;

import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Produces bounded, content-free diagnostics for read-only tool failures. */
public final class ToolFailureDiagnostics {

    private ToolFailureDiagnostics() {
    }

    public static Diagnostic describe(String toolName, Throwable failure) {
        var root = rootCause(failure);
        Integer status = findStatus(failure);
        String category;
        if (root instanceof SocketTimeoutException || root.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
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
                "get_vehicle_provenance", "get_catalog_status").contains(name)) {
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
