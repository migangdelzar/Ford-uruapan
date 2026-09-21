package com.ford.service.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    CapabilityDiagnostics capabilityDiagnostics(
            @Value("${ford.ai.cloud-enabled:false}") boolean aiCloudEnabled,
            @Value("${ford.ai.local-enabled:true}") boolean aiLocalEnabled,
            @Value("${spring.ai.model.chat:ollama}") String aiProvider,
            @Value("${ford.conversations.human-handoff.enabled:true}") boolean humanHandoffEnabled,
            @Value("${ford.channels.meta.delivery.enabled:false}") boolean metaOutboundEnabled,
            @Value("${ford.outbox.enabled:false}") boolean outboxEnabled,
            @Value("${management.tracing.export.enabled:false}") boolean tracesEnabled,
            @Value("${management.otlp.metrics.export.enabled:false}") boolean metricsEnabled) {
        return new CapabilityDiagnostics(aiCloudEnabled, aiLocalEnabled, aiProvider,
                humanHandoffEnabled, metaOutboundEnabled, outboxEnabled,
                tracesEnabled, metricsEnabled);
    }
}
