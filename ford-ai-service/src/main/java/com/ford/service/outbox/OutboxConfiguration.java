package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ford.outbox.enabled", havingValue = "true")
public class OutboxConfiguration {

    @Bean
    @ConditionalOnBean(OutboxDeliveryRouter.class)
    OutboxPublisher outboxPublisher(OutboxStore store, OutboxDeliveryRouter router,
                                    AiObservationService observations,
                                    @Value("${ford.outbox.max-attempts:5}") int maxAttempts,
                                    @Value("${ford.outbox.lease-seconds:30}") long leaseSeconds,
                                    @Value("${ford.outbox.backoff-base-seconds:2}") long backoffBaseSeconds,
                                    @Value("${ford.outbox.backoff-max-seconds:300}") long backoffMaxSeconds) {
        return new OutboxPublisher(store, router, maxAttempts,
                Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(backoffBaseSeconds),
                Duration.ofSeconds(backoffMaxSeconds), observations);
    }
}
