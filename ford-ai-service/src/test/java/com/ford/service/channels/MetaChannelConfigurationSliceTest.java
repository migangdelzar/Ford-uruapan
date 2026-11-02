package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.MetaChannelConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = MetaChannelConfigurationSliceTest.ProbeController.class)
@Import({MetaChannelConfiguration.class, MetaChannelConfigurationSliceTest.JacksonConfiguration.class})
class MetaChannelConfigurationSliceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void usesOneBootManagedObjectMapper() {
        assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JacksonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @RestController
    static class ProbeController {
    }
}
