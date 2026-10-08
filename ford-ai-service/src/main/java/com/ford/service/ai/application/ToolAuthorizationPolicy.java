package com.ford.service.ai.application;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ToolAuthorizationPolicy {

    private static final List<String> DENIED_TERMS = List.of(
            "write", "delete", "update", "insert", "sql", "send", "message", "handoff", "score", "ingest",
            "book", "purchase", "buy", "pay", "charge", "schedule", "appointment", "create", "cancel", "transfer",
            "execute");

    private final List<ToolCallback> tools;

    public ToolAuthorizationPolicy() {
        this(List.of());
    }

    public ToolAuthorizationPolicy(List<ToolCallback> tools) {
        this.tools = List.copyOf(Objects.requireNonNull(tools));
    }

    public List<ToolCallback> readOnlyTools(AiExecutionContext context) {
        return readOnlyTools(context, null, new ToolResultTracker());
    }

    public List<ToolCallback> readOnlyTools(AiExecutionContext context, Set<String> allowedNames) {
        return readOnlyTools(context, allowedNames, new ToolResultTracker());
    }

    public List<ToolCallback> readOnlyTools(AiExecutionContext context, Set<String> allowedNames,
                                            ToolResultTracker resultTracker) {
        if (context == null || context.tenantId() == null) {
            throw new IllegalArgumentException("tenant context is required");
        }
        Objects.requireNonNull(resultTracker, "tool result tracker is required");
        return tools.stream()
                .filter(tool -> isReadOnly(tool.getToolDefinition().name()))
                .filter(tool -> allowedNames == null || allowedNames.contains(tool.getToolDefinition().name()))
                .<ToolCallback>map(tool -> new SingleAttemptToolCallback(tool, resultTracker))
                .toList();
    }

    public Set<String> availableReadOnlyToolNames() {
        return tools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .filter(ToolAuthorizationPolicy::isReadOnly)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isReadOnly(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return DENIED_TERMS.stream().noneMatch(normalized::contains);
    }

    private static final class SingleAttemptToolCallback implements ToolCallback {
        private static final String ALREADY_ATTEMPTED =
                "Esta consulta ya se intentó en este turno. Usa el resultado recibido o explica qué dato no se pudo confirmar.";
        private final ToolCallback delegate;
        private final ToolResultTracker resultTracker;
        private final AtomicBoolean invoked = new AtomicBoolean();

        private SingleAttemptToolCallback(ToolCallback delegate, ToolResultTracker resultTracker) {
            this.delegate = delegate;
            this.resultTracker = resultTracker;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            if (!invoked.compareAndSet(false, true)) return ALREADY_ATTEMPTED;
            var name = getToolDefinition().name();
            resultTracker.attempted(name);
            try {
                var result = delegate.call(input);
                resultTracker.resultReturned(name, result);
                return result;
            } catch (RuntimeException exception) {
                resultTracker.failed(name);
                throw exception;
            }
        }

        @Override
        public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            if (!invoked.compareAndSet(false, true)) return ALREADY_ATTEMPTED;
            var name = getToolDefinition().name();
            resultTracker.attempted(name);
            try {
                var result = delegate.call(input, context);
                resultTracker.resultReturned(name, result);
                return result;
            } catch (RuntimeException exception) {
                resultTracker.failed(name);
                throw exception;
            }
        }
    }
}
