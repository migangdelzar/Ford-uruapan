package com.ford.service.ai.application;

import java.time.Duration;

@FunctionalInterface
public interface ProviderRetryDelay {
    void delay(Duration duration);
}
