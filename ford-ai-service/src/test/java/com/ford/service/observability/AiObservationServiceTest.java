package com.ford.service.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiObservationServiceTest {

    @Test
    void recordsBoundedTagsAndReturnsTheBusinessResult() {
        var observations = new ArrayList<Observation.Context>();
        var registry = registry(observations);
        var service = new DefaultAiObservationService(registry);

        var result = service.observe(
                "ford.ai.conversation",
                Map.of("channel", "whatsapp", "provider", "openai", "tenant", "customer-1"),
                () -> "answer");

        assertThat(result).isEqualTo("answer");
        assertThat(observations).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo("ford.ai.conversation");
            assertThat(context.getLowCardinalityKeyValue("channel").getValue()).isEqualTo("whatsapp");
            assertThat(context.getLowCardinalityKeyValue("provider").getValue()).isEqualTo("openai");
            assertThat(context.getLowCardinalityKeyValue("tenant")).isNull();
        });
    }

    @Test
    void rethrowsBusinessFailureAfterRecordingTheObservation() {
        var observations = new ArrayList<Observation.Context>();
        var registry = registry(observations);
        var service = new DefaultAiObservationService(registry);
        var failure = new IllegalStateException("provider unavailable");

        assertThatThrownBy(() -> service.observe("ford.ai.model", Map.of("provider", "ollama"), () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(observations).singleElement().satisfies(context -> assertThat(context.getError()).isSameAs(failure));
    }

    private static ObservationRegistry registry(ArrayList<Observation.Context> observations) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                observations.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }
}
