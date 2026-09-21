package com.ford.service.observability;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.channels.adapter.out.meta.MetaDeliveryProperties;
import com.ford.service.conversations.application.ConversationProperties;
import com.ford.service.outbox.OutboxProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    CapabilityDiagnostics capabilityDiagnostics(
            AiProperties aiProperties,
            ConversationProperties conversationProperties,
            OutboxProperties outboxProperties,
            ObjectProvider<MetaDeliveryProperties> metaDeliveryProperties,
            @Value("${management.tracing.export.enabled:false}") boolean tracesEnabled,
            @Value("${management.otlp.metrics.export.enabled:false}") boolean metricsEnabled) {
        var delivery = metaDeliveryProperties.getIfAvailable();
        return new CapabilityDiagnostics(aiProperties, conversationProperties, outboxProperties,
                delivery != null && delivery.isEnabled(), tracesEnabled, metricsEnabled);
    }
}
