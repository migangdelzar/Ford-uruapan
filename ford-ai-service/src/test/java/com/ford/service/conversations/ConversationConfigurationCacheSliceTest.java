package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.adapter.out.RedisCachedConversationMemoryPort;
import com.ford.service.conversations.application.ConversationConfiguration;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ConversationConfigurationCacheSliceTest.ProbeController.class,
        properties = "ford.memory.cache-enabled=true")
@Import({ConversationConfiguration.class, ConversationConfigurationCacheSliceTest.MetricsConfiguration.class})
class ConversationConfigurationCacheSliceTest {

    @Autowired
    ApplicationContext context;

    @MockitoBean
    ConversationRepository conversationRepository;

    @MockitoBean
    AssistantService assistantService;

    @MockitoBean
    ConversationDecisionRepository conversationDecisionRepository;

    @MockitoBean
    JdbcConversationMemoryRepository durableMemory;

    @MockitoBean
    StringRedisTemplate redis;

    @MockitoBean
    ObjectMapper objectMapper;

    @Test
    void selectsRedisDecoratorWhenHotMemoryIsEnabled() {
        assertThat(context.getBean(ConversationMemoryPort.class))
                .isInstanceOf(RedisCachedConversationMemoryPort.class);
    }

    @RestController
    static class ProbeController {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
