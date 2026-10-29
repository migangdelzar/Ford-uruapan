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
        var failure = new IllegalStateException("secret-token customer-phone 4521234567 private response body");

        assertThatThrownBy(() -> service.observe("ford.ai.model", Map.of("provider", "ollama"), () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(observations).singleElement().satisfies(context -> {
            assertThat(context.getError()).isNotSameAs(failure);
            assertThat(context.getError().getMessage()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    void recordsBoundedProviderAttemptDimensionsWithoutCustomerOrPayloadData() {
        var observations = new ArrayList<Observation.Context>();
        var service = new DefaultAiObservationService(registry(observations));

        service.event("ford.ai.provider.attempt", Map.of(
                "provider", "openrouter/free",
                "outcome", "retry",
                "failure_category", "rate_limited",
                "tenant", "00000000-0000-0000-0000-000000000001",
                "conversation_id", "private-conversation",
                "tool.result", "private source payload"));

        assertThat(observations).singleElement().satisfies(context -> {
            assertThat(context.getLowCardinalityKeyValue("provider").getValue()).isEqualTo("openrouter");
            assertThat(context.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("retry");
            assertThat(context.getLowCardinalityKeyValue("failure_category").getValue()).isEqualTo("rate_limited");
            assertThat(context.getAllKeyValues().stream().map(io.micrometer.common.KeyValue::getKey))
                    .containsExactlyInAnyOrder("provider", "outcome", "failure_category");
        });
    }

    @Test
    void recordsBoundedAnswerConfidenceAndSourceEvidenceSignals() {
        var observations = new ArrayList<Observation.Context>();
        var service = new DefaultAiObservationService(registry(observations));

        service.event("ford.ai.answer.assessment", Map.of(
                "provider", "groq",
                "confidence_band", "high",
                "evidence_status", "read_only_tool_returned",
                "quality_outcome", "accepted",
                "customer_text", "private answer"));

        assertThat(observations).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo("ford.ai.answer.assessment");
            assertThat(context.getLowCardinalityKeyValue("provider").getValue()).isEqualTo("other");
            assertThat(context.getLowCardinalityKeyValue("confidence_band").getValue()).isEqualTo("high");
            assertThat(context.getLowCardinalityKeyValue("evidence_status").getValue())
                    .isEqualTo("read_only_tool_returned");
            assertThat(context.getLowCardinalityKeyValue("quality_outcome").getValue()).isEqualTo("accepted");
            assertThat(context.getAllKeyValues().stream().map(io.micrometer.common.KeyValue::getKey))
                    .containsExactlyInAnyOrder("provider", "confidence_band", "evidence_status", "quality_outcome");
        });
    }

    @Test
    void recordsBoundedPerOutputAttemptStageAndOrdinal() {
        var observations = new ArrayList<Observation.Context>();
        var service = new DefaultAiObservationService(registry(observations));

        service.event("ford.ai.answer.attempt", Map.of(
                "attempt_stage", "quality_repair",
                "attempt", "2",
                "confidence_band", "high",
                "evidence_status", "knowledge_documents_returned",
                "quality_outcome", "pending",
                "conversation_id", "private-conversation"));
        service.event("ford.ai.answer.attempt", Map.of(
                "attempt_stage", "untrusted-stage",
                "attempt", "999",
                "quality_outcome", "unknown"));

        assertThat(observations).hasSize(2);
        assertThat(observations.getFirst().getName()).isEqualTo("ford.ai.answer.attempt");
        assertThat(observations.getFirst().getAllKeyValues())
                .extracting(io.micrometer.common.KeyValue::getKey)
                .containsExactlyInAnyOrder("attempt_stage", "attempt", "confidence_band",
                        "evidence_status", "quality_outcome");
        assertThat(observations.getFirst().getLowCardinalityKeyValue("evidence_status").getValue())
                .isEqualTo("knowledge_documents_returned");
        assertThat(observations.getFirst().getLowCardinalityKeyValue("attempt_stage").getValue())
                .isEqualTo("quality_repair");
        assertThat(observations.getFirst().getLowCardinalityKeyValue("attempt").getValue()).isEqualTo("2");
        assertThat(observations.get(1).getLowCardinalityKeyValue("attempt_stage").getValue()).isEqualTo("other");
        assertThat(observations.get(1).getLowCardinalityKeyValue("attempt").getValue()).isEqualTo("other");
        assertThat(observations.get(1).getLowCardinalityKeyValue("quality_outcome").getValue()).isEqualTo("other");
    }

    @Test
    void observationInfrastructureFailureNeverChangesBusinessBehavior() {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStart(Observation.Context context) {
                throw new IllegalStateException("telemetry unavailable");
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        var service = new DefaultAiObservationService(registry);

        assertThat(service.observe("ford.ai.conversation", Map.of("outcome", "success"), () -> "reply"))
                .isEqualTo("reply");
        org.assertj.core.api.Assertions.assertThatCode(() ->
                service.event("ford.outbox.delivery", Map.of("outcome", "success")))
                .doesNotThrowAnyException();
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
