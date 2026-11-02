package com.ford.service.conversations.application.graph;

import com.ford.service.ai.api.AssistantTurnUnavailableException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

public final class ConversationGraphExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String failureCategory;
    private final String rootCauseType;

    ConversationGraphExecutionException(RuntimeException failure) {
        super("Conversation turn processor failed", failure);
        var root = rootCause(failure);
        this.rootCauseType = safeType(root.getClass().getSimpleName());
        this.failureCategory = category(failure);
    }

    public String failureCategory() {
        return failureCategory;
    }

    public String rootCauseType() {
        return rootCauseType;
    }

    private static String category(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof AssistantTurnUnavailableException unavailable) {
                return "assistant_unavailable_" + unavailable.disposition().name().toLowerCase(java.util.Locale.ROOT);
            }
            if (current instanceof SQLException) return "database_failure";
        }
        return "graph_failure";
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (current.getCause() != null && seen.add(current)) current = current.getCause();
        return current;
    }

    private static String safeType(String simpleName) {
        if (simpleName != null && simpleName.matches("[A-Za-z][A-Za-z0-9_$]{0,79}")) return simpleName;
        return "UnknownFailure";
    }
}
