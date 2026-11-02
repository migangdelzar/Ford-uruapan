package com.ford.service.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class MicrometerAiMetrics implements AiMetrics {
    private static final Set<String> PROVIDERS = Set.of("openai", "ollama");
    private static final Set<String> TOOLS = Set.of("catalog", "finance", "knowledge");
    private final MeterRegistry registry;

    public MicrometerAiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void modelCompleted(ModelObservation observation) {
        var provider = bounded(observation.provider(), PROVIDERS, "other");
        registry.timer("ford.ai.model.duration", "provider", provider, "success", Boolean.toString(observation.success()))
                .record(java.time.Duration.ofMillis(Math.max(0, observation.durationMs())));
    }

    @Override
    public void toolCompleted(ToolObservation observation) {
        var tool = bounded(observation.tool(), TOOLS, "other");
        registry.timer("ford.ai.tool.duration", "tool", tool, "success", Boolean.toString(observation.success()))
                .record(java.time.Duration.ofMillis(Math.max(0, observation.durationMs())));
    }

    @Override
    public void handoffCreated(HandoffObservation observation) {
        registry.counter("ford.ai.handoff.created", "band", bounded(observation.band(), Set.of("HOT", "WARM", "COLD"), "other"))
                .increment();
    }

    private static String bounded(String value, Set<String> allowed, String fallback) {
        return allowed.contains(value) ? value : fallback;
    }
}
