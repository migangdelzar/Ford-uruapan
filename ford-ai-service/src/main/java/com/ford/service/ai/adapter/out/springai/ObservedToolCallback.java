package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.ai.application.ProviderRetryDelay;
import com.ford.service.ai.application.ThreadBasedProviderRetryDelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.Objects;

/** Adds content-safe lifecycle logs around a Spring AI tool callback. */
public final class ObservedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ObservedToolCallback.class);
    private final ToolCallback delegate;
    private final MeterRegistry meterRegistry;
    private final ProviderRetryDelay retryDelay;

    public ObservedToolCallback(ToolCallback delegate, MeterRegistry meterRegistry) {
        this(delegate, meterRegistry, new ThreadBasedProviderRetryDelay());
    }

    public ObservedToolCallback(ToolCallback delegate, MeterRegistry meterRegistry, ProviderRetryDelay retryDelay) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.meterRegistry = meterRegistry;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return observe(null, () -> delegate.call(input));
    }

    @Override
    public String call(String input, ToolContext context) {
        return observe(context, () -> delegate.call(input, context));
    }

    private String observe(ToolContext context, ToolInvocation invocation) {
        var toolName = delegate.getToolDefinition().name();
        var tenantId = safeContextId(context, "tenantId");
        var conversationId = safeContextId(context, "conversationId");
        var started = System.nanoTime();
        var toolFamily = ToolFailureDiagnostics.toolFamily(toolName);
        log.info("catalog tool call started: tool={} tenantId={} conversationId={}",
                toolName, tenantId, conversationId);
        var attempt = 1;
        while (true) {
            var attemptStarted = System.nanoTime();
            try {
                var result = invocation.invoke();
                record(toolFamily, "success", null, attemptStarted);
                log.info("catalog tool call completed: tool={} tenantId={} conversationId={} outcome=success attempts={} durationMs={}",
                        toolName, tenantId, conversationId, attempt, elapsedMs(started));
                return result;
            } catch (RuntimeException exception) {
                var diagnostics = ToolFailureDiagnostics.describe(toolName, exception);
                if (attempt == 1 && ToolFailureDiagnostics.canRetryImmediately(diagnostics, exception)) {
                    record(diagnostics.tool(), "retry", diagnostics.category(), attemptStarted);
                    log.warn("catalog tool attempt failed: tool={} tenantId={} conversationId={} attempt=1 outcome=retry failureCategory={} rootCauseType={} status={}",
                            toolName, tenantId, conversationId, diagnostics.category(), diagnostics.rootCauseType(),
                            diagnostics.status() == null ? "-" : diagnostics.status());
                    try {
                        Duration delay = ToolFailureDiagnostics.immediateRetryDelay(exception);
                        retryDelay.delay(delay);
                    } catch (RuntimeException retryWaitFailure) {
                        throw new AssistantTurnUnavailableException(AssistantTurnUnavailableException.Disposition.RETRY);
                    }
                    attempt++;
                    continue;
                }
                record(diagnostics.tool(), "failure", diagnostics.category(), attemptStarted);
                log.warn("catalog tool call completed: tool={} tenantId={} conversationId={} outcome=failure failureCategory={} rootCauseType={} status={} attempts={} durationMs={}",
                        toolName, tenantId, conversationId, diagnostics.category(), diagnostics.rootCauseType(),
                        diagnostics.status() == null ? "-" : diagnostics.status(), attempt, elapsedMs(started));
                throw new AssistantTurnUnavailableException(ToolFailureDiagnostics.disposition(diagnostics));
            }
        }
    }

    private void record(String tool, String outcome, String failureCategory, long started) {
        if (meterRegistry == null) {
            return;
        }
        var builder = Timer.builder("ford.ai.tool.attempt")
                .tag("tool", tool)
                .tag("outcome", outcome);
        if (failureCategory != null) {
            builder.tag("failure_category", failureCategory);
        }
        builder.register(meterRegistry).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private static String safeContextId(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return "-";
        }
        var value = context.getContext().get(key);
        if (value instanceof java.util.UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof String text && text.matches("[0-9a-fA-F-]{36}")) {
            return text;
        }
        return "-";
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    @FunctionalInterface
    private interface ToolInvocation {
        String invoke();
    }
}
