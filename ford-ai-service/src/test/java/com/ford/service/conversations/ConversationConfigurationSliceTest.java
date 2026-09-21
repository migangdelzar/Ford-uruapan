package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationAssistantAdapter;
import com.ford.service.conversations.application.ConversationConfiguration;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ford.service.ai.application.AssistantService;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ConversationConfigurationSliceTest.ProbeController.class)
@Import(ConversationConfiguration.class)
class ConversationConfigurationSliceTest {

    @Autowired
    ApplicationContext context;

    @MockitoBean
    ConversationRepository conversationRepository;

    @MockitoBean
    AssistantService assistantService;

    @Test
    void registersTheAssistantAndWorkflowWithConfiguredDependencies() {
        assertThat(context.getBean(ConversationAssistant.class))
                .isInstanceOf(ConversationAssistantAdapter.class);
        assertThat(context.getBean(ConversationWorkflow.class)).isNotNull();
    }

    @RestController
    static class ProbeController {
    }
}
