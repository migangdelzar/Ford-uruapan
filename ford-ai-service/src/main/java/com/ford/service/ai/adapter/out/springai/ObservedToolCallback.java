package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.ai.application.ProviderRetryDelay;
import com.ford.service.ai.application.ThreadBasedProviderRetryDelay;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.ToolResultTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();
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
        var normalizedInput = dropMalformedYearFilter(delegate.getToolDefinition().name(), input);
        return observe(null, input, () -> delegate.call(normalizedInput));
    }

    @Override
    public String call(String input, ToolContext context) {
        var toolName = delegate.getToolDefinition().name();
        var normalizedInput = dropUnconfirmedVehicleFilters(toolName,
                dropMalformedYearFilter(toolName, input), context);
        return observe(context, input, () -> delegate.call(normalizedInput, context));
    }

    private static String dropUnconfirmedVehicleFilters(String toolName, String input, ToolContext context) {
        if (!"search_vehicles".equals(toolName) || context == null || context.getContext() == null
                || !context.getContext().containsKey("userMessage")) return input;
        try {
            var arguments = objectMapper.readTree(input);
            if (!(arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return input;
            var userMessage = String.valueOf(context.getContext().get("userMessage"));
            boolean droppedFilter = false;
            var confirmedModel = context.getContext().get("confirmedVehicleModel");
            var model = object.get("model");
            if (model != null && !model.isNull()
                    && (!(confirmedModel instanceof String value)
                    || !value.equalsIgnoreCase(model.asText()))) {
                object.putNull("model");
                droppedFilter = true;
            }
            var confirmedYear = context.getContext().get("confirmedVehicleYear");
            var modelYear = object.get("modelYear");
            if (modelYear != null && !modelYear.isNull()
                    && (!(confirmedYear instanceof Number value)
                    || !modelYear.isIntegralNumber() || value.intValue() != modelYear.intValue())) {
                object.putNull("modelYear");
                droppedFilter = true;
            }
            for (var field : java.util.List.of("category", "bodyType", "fuelType")) {
                var filter = object.get(field);
                if (filter != null && !filter.isNull() && hasText(filter)
                        && !containsWord(userMessage, filter.asText())) {
                    object.putNull(field);
                    droppedFilter = true;
                }
            }
            for (var field : java.util.List.of("minPrice", "maxPrice")) {
                var filter = object.get(field);
                if (filter != null && !filter.isNull() && !containsAmount(userMessage, filter.asText())) {
                    object.putNull(field);
                    droppedFilter = true;
                }
            }
            if (droppedFilter && !hasText(object.get("query"))) {
                object.put("query", userMessage);
            }
            return objectMapper.writeValueAsString(object);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return input;
        }
    }

    private static boolean containsWord(String message, String filter) {
        return java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                        + java.util.regex.Pattern.quote(filter.trim()) + "(?![\\p{L}\\p{N}])")
                .matcher(message).find();
    }

    private static boolean containsAmount(String message, String filter) {
        var digits = filter.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return false;
        var messageDigits = message.replaceAll("[^0-9]", " ");
        return java.util.Arrays.stream(messageDigits.split("\\s+"))
                .anyMatch(digits::equals);
    }

    private static String dropMalformedYearFilter(String toolName, String input) {
        if (!"search_vehicles".equals(toolName) && !"list_promotions".equals(toolName)) return input;
        try {
            var arguments = objectMapper.readTree(input);
            if (!(arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return input;
            var modelYear = object.get("modelYear");
            if (modelYear == null || modelYear.isNull() || modelYear.isIntegralNumber()) return input;
            object.putNull("modelYear");
            return objectMapper.writeValueAsString(object);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return input;
        }
    }

    private String observe(ToolContext context, String input, ToolInvocation invocation) {
        var toolName = delegate.getToolDefinition().name();
        var tenantId = safeContextId(context, "tenantId");
        var conversationId = safeContextId(context, "conversationId");
        var started = System.nanoTime();
        var toolFamily = ToolFailureDiagnostics.toolFamily(toolName);
        var trace = traceCollector(context);
        recordTrace(trace, toolName, "tool_call_started", "started", null);
        log.info("catalog tool call started: tool={} tenantId={} conversationId={}",
                toolName, tenantId, conversationId);
        var inputShape = safeToolInputShape(toolName, input);
        if (!"-".equals(inputShape)) {
            log.info("catalog tool input shape: tool={} tenantId={} conversationId={} toolInputShape={}",
                    toolName, tenantId, conversationId, inputShape);
        }
        var attempt = 1;
        while (true) {
            var attemptStarted = System.nanoTime();
            try {
                var result = invocation.invoke();
                record(toolFamily, "success", null, attemptStarted);
                var outcome = isAmbiguousVehicleTrim(toolName, result) ? "ambiguous" : "returned";
                recordTrace(trace, toolName, outcome.equals("ambiguous")
                        ? "vehicle_trim_ambiguous" : "tool_returned", outcome, evidenceCount(result));
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
                recordTrace(trace, toolName, "tool_failed", "failed", null);
                log.warn("catalog tool call completed: tool={} tenantId={} conversationId={} outcome=failure failureCategory={} rootCauseType={} status={} attempts={} durationMs={} searchInput={}",
                        toolName, tenantId, conversationId, diagnostics.category(), diagnostics.rootCauseType(),
                        diagnostics.status() == null ? "-" : diagnostics.status(), attempt, elapsedMs(started),
                        safeSearchInputSummary(toolName, input));
                throw new AssistantTurnUnavailableException(ToolFailureDiagnostics.disposition(diagnostics));
            }
        }
    }

    private static DecisionTraceCollector traceCollector(ToolContext context) {
        if (context == null || context.getContext() == null) return null;
        var value = context.getContext().get("decisionTraceCollector");
        return value instanceof DecisionTraceCollector collector ? collector : null;
    }

    private static void recordTrace(DecisionTraceCollector collector, String toolName, String reason,
                                    String outcome, Integer evidenceCount) {
        if (collector == null) return;
        collector.record(new DecisionTraceEvent("tool", reason, null, "none", toolName,
                outcome, evidenceCount, java.time.Instant.now()));
    }

    private static Integer evidenceCount(String result) {
        return ToolResultTracker.evidenceCount(result);
    }

    private static boolean isAmbiguousVehicleTrim(String toolName, String result) {
        if (!"get_vehicle".equals(toolName) || result == null || result.isBlank()) return false;
        try {
            var root = objectMapper.readTree(result);
            var choices = root.get("choices");
            return root.hasNonNull("model") && root.hasNonNull("modelYear")
                    && choices != null && choices.isArray() && choices.size() > 1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return false;
        }
    }

    private static boolean hasSource(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isObject()) {
            for (var field = node.fields(); field.hasNext();) {
                var entry = field.next();
                if (java.util.Set.of("sourceUrl", "source_url", "modelPageUrl", "model_page_url",
                        "priceSourceUrl", "price_source_url", "technicalSheetUrl", "technical_sheet_url")
                        .contains(entry.getKey()) && entry.getValue().isTextual()
                        && !entry.getValue().asText().isBlank()) return true;
                if (hasSource(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (var child : node) if (hasSource(child)) return true;
        }
        return false;
    }

    private static String safeSearchInputSummary(String toolName, String input) {
        if (!"search_vehicles".equals(toolName)) return "-";
        try {
            JsonNode arguments = objectMapper.readTree(input);
            boolean queryPresent = hasText(arguments.get("query"));
            boolean modelPresent = hasText(arguments.get("model"));
            JsonNode limit = arguments.get("limit");
            String limitSummary = limit == null ? "absent"
                    : limit.isNull() ? "null"
                    : limit.isIntegralNumber() ? limit.asText() : "non_integer";
            return "queryPresent=" + queryPresent + ",modelPresent=" + modelPresent + ",limit=" + limitSummary;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return "unparseable";
        }
    }

    private static String safeToolInputShape(String toolName, String input) {
        if (!"search_vehicles".equals(toolName) && !"list_promotions".equals(toolName)
                && !"get_vehicle".equals(toolName)) return "-";
        try {
            JsonNode arguments = objectMapper.readTree(input);
            if ("get_vehicle".equals(toolName)) {
                var trim = arguments.get("trim");
                var trimChars = trim != null && trim.isTextual() ? trim.asText().length()
                        : trim == null || trim.isNull() ? "absent" : "non_string";
                return "modelPresent=" + hasText(arguments.get("model"))
                        + ",year=" + safeInteger(arguments.get("year"))
                        + ",trimPresent=" + hasText(trim)
                        + ",trimChars=" + trimChars;
            }
            if ("search_vehicles".equals(toolName)) {
                return "queryPresent=" + hasText(arguments.get("query"))
                        + ",modelPresent=" + hasText(arguments.get("model"))
                        + ",modelYear=" + safeInteger(arguments.get("modelYear"))
                        + ",categoryPresent=" + hasText(arguments.get("category"))
                        + ",bodyTypePresent=" + hasText(arguments.get("bodyType"))
                        + ",fuelTypePresent=" + hasText(arguments.get("fuelType"))
                        + ",minPricePresent=" + arguments.hasNonNull("minPrice")
                        + ",maxPricePresent=" + arguments.hasNonNull("maxPrice")
                        + ",limit=" + safeInteger(arguments.get("limit"));
            }
            var query = arguments.path("query").isTextual() ? arguments.path("query").asText() : "";
            var asOf = arguments.path("asOf");
            var asOfPresent = asOf.isTextual() && !asOf.asText().isBlank();
            var asOfMatchesQuery = asOfPresent && query.contains(asOf.asText());
            return "modelPresent=" + hasText(arguments.get("model"))
                    + ",modelYear=" + safeInteger(arguments.get("modelYear"))
                    + ",asOfPresent=" + asOfPresent
                    + ",asOfMatchesQuery=" + asOfMatchesQuery
                    + ",activeOnly=" + safeBoolean(arguments.get("activeOnly"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return "unparseable";
        }
    }

    private static String safeInteger(JsonNode value) {
        if (value == null) return "absent";
        if (value.isNull()) return "null";
        return value.isIntegralNumber() ? value.asText() : "non_integer";
    }

    private static String safeBoolean(JsonNode value) {
        if (value == null) return "absent";
        if (value.isNull()) return "null";
        return value.isBoolean() ? value.asText() : "non_boolean";
    }

    private static boolean hasText(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank();
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
