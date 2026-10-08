package com.ford.service.ai;

import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
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
        "ford.ai.local-enabled=false",
        "spring.ai.typesafe.api-key=",
        "spring.ai.retry.max-attempts=1",
        "spring.ai.openai.max-retries=0"
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

    @Test
    void usesRulesDecisionProviderWhenTypeSafeIsNotConfigured() {
        assertThat(context.getBean(ConversationDecisionPort.class))
                .isInstanceOf(RulesConversationDecisionAdapter.class);
        assertThat(context.getBean(ConversationDecisionService.class)).isNotNull();
    }

    @Test
    void configuresProviderRetryBudgetAndDisablesNestedSpringAiRetries() {
        var properties = context.getBean(AiProperties.class);

        assertThat(properties.providerMaxAttempts()).isEqualTo(5);
        assertThat(properties.providerMaxRetryAfter()).isEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(context.getEnvironment().getProperty("spring.ai.retry.max-attempts", Integer.class)).isEqualTo(1);
        assertThat(context.getEnvironment().getProperty("spring.ai.openai.max-retries", Integer.class)).isZero();
    }

    @RestController
    static class ProbeController {
    }
}
