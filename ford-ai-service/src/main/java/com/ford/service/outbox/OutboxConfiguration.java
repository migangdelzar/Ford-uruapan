package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ford.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {

    @Bean
    OutboxPublisher outboxPublisher(OutboxStore store, OutboxDeliveryRouter router,
                                    AiObservationService observations,
                                    OutboxProperties properties) {
        return new OutboxPublisher(store, router, properties.maxAttempts(),
                Duration.ofSeconds(properties.leaseSeconds()),
                Duration.ofSeconds(properties.backoffBaseSeconds()),
                Duration.ofSeconds(properties.backoffMaxSeconds()), observations);
    }

    @Bean
    OutboxDeliveryJob outboxDeliveryJob(OutboxPublisher publisher) {
        return new OutboxDeliveryJob(publisher);
    }

    @Bean
    OutboxRecoveryJob outboxRecoveryJob(OutboxStore store) {
        return new OutboxRecoveryJob(store);
    }
}
