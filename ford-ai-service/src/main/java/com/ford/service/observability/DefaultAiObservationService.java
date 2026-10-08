package com.ford.service.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class DefaultAiObservationService implements AiObservationService {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "channel", "provider", "model", "tool", "outcome", "lead_band", "operation",
            "failure_category", "confidence_band", "evidence_status", "quality_outcome");
    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "ford.ai.conversation", "ford.ai.model", "ford.ai.provider.attempt", "ford.ai.answer.assessment",
            "ford.conversation.inbound", "ford.conversation.memory_projection",
            "ford.outbox.publish", "ford.outbox.delivery");

    private final ObservationRegistry registry;

    public DefaultAiObservationService(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public <T> T observe(String operation, Map<String, String> lowCardinalityTags, Supplier<T> action) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(action);
        var observation = start(operation, lowCardinalityTags);
        try {
            return action.get();
        } catch (RuntimeException | Error exception) {
            if (observation != null) {
                try {
                    observation.error(new RuntimeException(exception.getClass().getSimpleName()));
                } catch (RuntimeException ignored) {
                    // Telemetry must never replace or mask the business failure.
                }
            }
            throw exception;
        } finally {
            stop(observation);
        }
    }

    @Override
    public void event(String operation, Map<String, String> lowCardinalityTags) {
        Objects.requireNonNull(operation);
        stop(start(operation, lowCardinalityTags));
    }

    private Observation start(String operation, Map<String, String> tags) {
        try {
            var observation = Observation.start(boundedOperation(operation), registry);
            addBoundedTags(observation, tags);
            return observation;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void stop(Observation observation) {
        if (observation == null) {
            return;
        }
        try {
            observation.stop();
        } catch (RuntimeException ignored) {
            // Telemetry is best-effort and must not fail application work.
        }
    }

    private static void addBoundedTags(Observation observation, Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        tags.forEach((key, value) -> {
            if (ALLOWED_KEYS.contains(key) && value != null && !value.isBlank()) {
                observation.lowCardinalityKeyValue(KeyValue.of(key, boundedValue(key, value)));
            }
        });
    }

    private static String boundedValue(String key, String value) {
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        if (key.equals("operation")) {
            return ALLOWED_OPERATIONS.contains(normalized) ? normalized : "other";
        }
        if (key.equals("channel")) {
            return Set.of("whatsapp", "messenger", "instagram", "web").contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("provider")) {
            if (Set.of("openrouter", "openrouter-free", "openrouter/free").contains(normalized)) {
                return "openrouter";
            }
            return Set.of("openai", "ollama", "deterministic", "none").contains(normalized) ? normalized : "other";
        }
        if (key.equals("outcome")) {
            return Set.of("success", "failure", "fallback", "handoff", "retry", "dead_letter", "unavailable", "skipped", "withheld")
                    .contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("confidence_band")) {
            return Set.of("low", "medium", "high", "unknown").contains(normalized) ? normalized : "unknown";
        }
        if (key.equals("evidence_status")) {
            return Set.of("not_required", "read_only_tool_returned", "read_only_tool_returned_empty",
                    "read_only_tool_failed", "required_tool_not_called")
                    .contains(normalized) ? normalized : "other";
        }
        if (key.equals("quality_outcome")) {
            return Set.of("accepted", "withheld", "rejected").contains(normalized) ? normalized : "other";
        }
        if (key.equals("failure_category")) {
            return Set.of("rate_limited", "provider_unavailable", "network", "authentication",
                    "invalid_request", "provider_error", "source_unavailable", "persistence", "delivery_error",
                    "timeout", "tool_error")
                    .contains(normalized) ? normalized : "other";
        }
        if (key.equals("lead_band")) {
            return Set.of("hot", "warm", "cold").contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("tool")) {
            return Set.of("catalog", "finance", "knowledge").contains(normalized) ? normalized : "other";
        }
        if (key.equals("model")) {
            return Set.of("chat", "embedding").contains(normalized) ? normalized : "other";
        }
        return "other";
    }

    private static String boundedOperation(String operation) {
        var normalized = operation.strip().toLowerCase(Locale.ROOT);
        return ALLOWED_OPERATIONS.contains(normalized) ? normalized : "ford.ai.other";
    }
}
