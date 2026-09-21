package com.ford.service.ai;

import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.configuration.SpringAiConfiguration;
import com.ford.service.ai.adapter.out.springai.SpringAiProviderConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = SpringAiConfigurationSliceTest.ProbeController.class)
@Import({SpringAiConfiguration.class, SpringAiProviderConfiguration.class})
@org.springframework.test.context.TestPropertySource(properties = {
        "ford.ai.cloud-enabled=false",
        "ford.ai.local-enabled=false"
})
class SpringAiConfigurationSliceTest {

    private final ApplicationContext context;

    @Autowired
    SpringAiConfigurationSliceTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    void registersAssistantChainWhenNoProviderModelIsAvailable() {
        assertThat(context.getBean(AssistantOrchestrator.class)).isNotNull();
        assertThat(context.getBean(AssistantService.class)).isNotNull();
    }

    @RestController
    static class ProbeController {
    }
}
