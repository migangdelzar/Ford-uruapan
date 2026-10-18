package com.ford.service.channels.application;

import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InboundDebounceProperties.class)
public class InboundDebounceConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ford.channels.debounce", name = "enabled", havingValue = "true")
    InboundDebounceService inboundDebounceService(InboundDebounceRepository repository,
                                                   AcceptedInboundMessageRepository messages,
                                                   Clock clock,
                                                   InboundDebounceProperties properties) {
        return new InboundDebounceService(repository, messages, clock, properties.window());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.channels.debounce", name = "enabled", havingValue = "true")
    InboundDebounceScheduler inboundDebounceScheduler(InboundDebounceService service) {
        return new InboundDebounceScheduler(service);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.channels.debounce", name = "enabled", havingValue = "true")
    InboundDebounceDispatcher inboundDebounceDispatcher(InboundDebounceRepository repository,
                                                         ApplicationEventPublisher publisher,
                                                         Clock clock,
                                                         InboundDebounceProperties properties) {
        return new InboundDebounceDispatcher(repository, publisher, clock, properties);
    }
}
