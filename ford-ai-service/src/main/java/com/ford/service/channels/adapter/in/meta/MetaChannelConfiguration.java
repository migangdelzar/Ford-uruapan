package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MetaWebhookProperties.class)
public class MetaChannelConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    ChannelAdapter whatsAppChannelAdapter(ObjectMapper objectMapper, MetaWebhookProperties properties) {
        return new WhatsAppChannelAdapter(objectMapper);
    }

    @Bean
    ChannelAdapter messengerChannelAdapter(ObjectMapper objectMapper, MetaWebhookProperties properties) {
        return new MessengerChannelAdapter(objectMapper);
    }

    @Bean
    ChannelAdapter instagramChannelAdapter(ObjectMapper objectMapper, MetaWebhookProperties properties) {
        return new InstagramChannelAdapter(objectMapper);
    }
}
