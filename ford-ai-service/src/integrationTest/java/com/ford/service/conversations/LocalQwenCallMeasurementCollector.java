package com.ford.service.conversations;

import com.ford.service.observability.AiProviderCallObserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory, content-free provider measurements for the opt-in local replay only. */
final class LocalQwenCallMeasurementCollector implements AiProviderCallObserver {
    private final CopyOnWriteArrayList<ProviderCallMeasurement> measurements = new CopyOnWriteArrayList<>();

    @Override
    public void record(ProviderCallMeasurement measurement) {
        measurements.add(measurement);
    }

    int mark() {
        return measurements.size();
    }

    List<ProviderCallMeasurement> since(int mark) {
        if (mark < 0 || mark > measurements.size()) {
            throw new IllegalArgumentException("provider measurement mark is outside collected range");
        }
        return List.copyOf(measurements.subList(mark, measurements.size()));
    }
}
