package com.ford.service.ai.application;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class ThreadBasedProviderRetryDelay implements ProviderRetryDelay {

    @Override
    public void delay(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider retry interrupted", exception);
        }
    }
}
