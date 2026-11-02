package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationAssistantAdapter;
import com.ford.service.conversations.application.ConversationConfiguration;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ford.service.ai.application.AssistantService;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ConversationConfigurationSliceTest.ProbeController.class,
        properties = "ford.conversations.workflow.graph-enabled=false")
@Import(ConversationConfiguration.class)
class ConversationConfigurationSliceTest {

    @Autowired
    ApplicationContext context;

    @MockitoBean
    ConversationRepository conversationRepository;

    @MockitoBean
    AssistantService assistantService;

    @MockitoBean
    ConversationMemoryPort conversationMemoryPort;

    @MockitoBean
    ConversationDecisionRepository conversationDecisionRepository;

    @MockitoBean
    ConversationTurnProcessor conversationTurnProcessor;

    @MockitoBean
    ConversationTurnLedgerPort conversationTurnLedger;

    @Test
    void registersTheAssistantAndWorkflowWithConfiguredDependencies() {
        assertThat(context.getBean(ConversationAssistant.class))
                .isInstanceOf(ConversationAssistantAdapter.class);
        assertThat(context.getBean(ConversationWorkflow.class)).isNotNull();
        assertThat(context.getBean(com.ford.service.conversations.application.graph.ConversationGraphRunner.class))
                .isNotNull();
    }

    @RestController
    static class ProbeController {
    }
}
