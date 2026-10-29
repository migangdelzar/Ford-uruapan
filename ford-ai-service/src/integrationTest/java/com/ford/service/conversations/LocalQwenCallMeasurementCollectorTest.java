package com.ford.service.conversations;

import com.ford.service.observability.AiProviderCallObserver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenCallMeasurementCollectorTest {
    @Test
    void returnsOnlyContentFreeProviderMeasurementsRecordedAfterTheMark() {
        var collector = new LocalQwenCallMeasurementCollector();
        collector.record(measurement("ollama", "qwen3.5:4b", 10));
        var mark = collector.mark();
        collector.record(measurement("ollama", "qwen3.5:4b", 20));

        assertThat(collector.since(mark)).containsExactly(measurement("ollama", "qwen3.5:4b", 20));
    }

    @Test
    void rejectsMarksOutsideTheCollectedRange() {
        var collector = new LocalQwenCallMeasurementCollector();

        assertThatIllegalArgumentException().isThrownBy(() -> collector.since(1));
    }

    private static AiProviderCallObserver.ProviderCallMeasurement measurement(String provider, String model,
                                                                                long duration) {
        return new AiProviderCallObserver.ProviderCallMeasurement(provider, model, duration,
                null, null, AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE, 0, 0, false,
                AiProviderCallObserver.Outcome.RETURNED);
    }
}
