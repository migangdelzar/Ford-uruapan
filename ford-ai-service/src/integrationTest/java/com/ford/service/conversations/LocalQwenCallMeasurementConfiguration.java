package com.ford.service.conversations;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class LocalQwenCallMeasurementConfiguration {
    @Bean
    LocalQwenCallMeasurementCollector localQwenCallMeasurementCollector() {
        return new LocalQwenCallMeasurementCollector();
    }
}
