package com.ford.service.channels.adapter.in.meta;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
@EnableConfigurationProperties(MetaWebhookProperties.class)
public class MetaChannelConfiguration {

    @Bean
    ChannelAdapter whatsAppChannelAdapter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new WhatsAppChannelAdapter(objectMapper);
    }

    @Bean
    ChannelAdapter messengerChannelAdapter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new MessengerChannelAdapter(objectMapper);
    }

    @Bean
    ChannelAdapter instagramChannelAdapter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new InstagramChannelAdapter(objectMapper);
    }

    @Bean
    MetaBindingStartupInitializer metaBindingStartupInitializer(
            ObjectProvider<JdbcClient> jdbcClients, MetaWebhookProperties properties) {
        return new MetaBindingStartupInitializer(jdbcClients, properties);
    }
}
