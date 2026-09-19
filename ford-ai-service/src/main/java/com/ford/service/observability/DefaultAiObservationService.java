package com.ford.service.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Component
public final class DefaultAiObservationService implements AiObservationService {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "channel", "provider", "model", "tool", "outcome", "lead_band", "operation");

    private final ObservationRegistry registry;

    public DefaultAiObservationService(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public <T> T observe(String operation, Map<String, String> lowCardinalityTags, Supplier<T> action) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(action);
        var observation = Observation.start(operation, registry);
        addBoundedTags(observation, lowCardinalityTags);
        try {
            return action.get();
        } catch (RuntimeException | Error exception) {
            observation.error(exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    @Override
    public void event(String operation, Map<String, String> lowCardinalityTags) {
        Objects.requireNonNull(operation);
        var observation = Observation.start(operation, registry);
        addBoundedTags(observation, lowCardinalityTags);
        observation.stop();
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
        var normalized = value.strip().toLowerCase();
        if (key.equals("channel")) {
            return Set.of("whatsapp", "messenger", "instagram", "web").contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("provider")) {
            return Set.of("openai", "ollama", "deterministic", "none").contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("outcome")) {
            return Set.of("success", "failure", "handoff").contains(normalized)
                    ? normalized : "other";
        }
        if (key.equals("lead_band")) {
            return Set.of("hot", "warm", "cold").contains(normalized)
                    ? normalized : "other";
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
