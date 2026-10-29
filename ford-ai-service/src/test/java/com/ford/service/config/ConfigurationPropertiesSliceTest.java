package com.ford.service.config;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.conversations.application.ConversationProperties;
import com.ford.service.outbox.OutboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ConfigurationPropertiesSliceTest.ProbeController.class)
@Import(ConfigurationPropertiesSliceTest.PropertiesConfiguration.class)
@TestPropertySource(properties = {
        "ford.ai.cloud-enabled=true",
        "ford.ai.local-enabled=false",
        "ford.ai.cloud-chat-provider=openai",
        "ford.ai.log-provider-errors=true",
        "ford.conversations.human-handoff.enabled=false",
        "ford.outbox.enabled=false"
})
class ConfigurationPropertiesSliceTest {

    private final AiProperties aiProperties;
    private final ConversationProperties conversationProperties;
    private final OutboxProperties outboxProperties;

    @Autowired
    ConfigurationPropertiesSliceTest(AiProperties aiProperties,
                                     ConversationProperties conversationProperties,
                                     OutboxProperties outboxProperties) {
        this.aiProperties = aiProperties;
        this.conversationProperties = conversationProperties;
        this.outboxProperties = outboxProperties;
    }

    @Test
    void bindsCapabilityPropertiesFromSpringEnvironment() {
        assertThat(aiProperties.cloudEnabled()).isTrue();
        assertThat(aiProperties.localEnabled()).isFalse();
        assertThat(aiProperties.cloudChatProvider()).isEqualTo("openai");
        assertThat(aiProperties.logProviderErrors()).isTrue();
        assertThat(conversationProperties.humanHandoffEnabled()).isFalse();
        assertThat(outboxProperties.enabled()).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiProperties.class, ConversationProperties.class, OutboxProperties.class})
    static class PropertiesConfiguration {
    }

    @RestController
    static class ProbeController {
    }
}
