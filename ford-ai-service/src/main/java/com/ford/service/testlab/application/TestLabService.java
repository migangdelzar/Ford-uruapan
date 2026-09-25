package com.ford.service.testlab.application;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class TestLabService {
    private final TestLabProperties properties;
    private final AtomicReference<FaultSettings> settings = new AtomicReference<>(FaultSettings.disabled());

    public TestLabService(TestLabProperties properties) {
        this.properties = properties;
    }

    public FaultSettings get() {
        requireEnabled();
        return settings.get();
    }

    public FaultSettings replace(FaultSettings next) {
        requireEnabled();
        if (next == null) throw new IllegalArgumentException("fault settings are required");
        settings.set(next);
        return next;
    }

    private void requireEnabled() {
        if (!properties.enabled()) throw new IllegalStateException("test lab is disabled");
    }

    public record FaultSettings(boolean aiTimeout, boolean aiError500, boolean rateLimit429,
                                boolean malformedOutput, boolean channelSendFailure,
                                boolean duplicateWebhook, boolean outOfOrder, boolean offline) {
        static FaultSettings disabled() {
            return new FaultSettings(false, false, false, false, false, false, false, false);
        }
    }
}
