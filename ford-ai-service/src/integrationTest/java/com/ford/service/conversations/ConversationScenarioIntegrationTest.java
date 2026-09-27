package com.ford.service.conversations;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.outbox.OutboxStore;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ApplicationModuleTest(module = "conversations", verifyAutomatically = false)
@ActiveProfiles("test")
@Import(ConversationScenarioIntegrationTest.TestBeans.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation_scenario;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.liquibase.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.modulith.runtime.verification-enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "ford.conversations.workflow.graph-enabled=false",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
class ConversationScenarioIntegrationTest {

    @Test
    void inboundEventProducesReplyForActiveBot(Scenario scenario) {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = TestBeans.repository;
        var assistant = TestBeans.assistant;
        when(repository.find(conversationId)).thenReturn(Optional.of(new ConversationSnapshot(
                tenantId, conversationId, Channel.WHATSAPP, "5214521234567",
                ConversationState.BOT_ACTIVE, 0, "¿Hay Ranger?", "wamid.inbound")));
        when(assistant.respond(any())).thenReturn(new AssistantReply("Sí, tenemos Ranger disponible.", false));
        when(repository.completeBotTurn(any(), any(Long.class), any())).thenReturn(true);

        scenario.publish(new InboundMessageAccepted(
                        UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                        "wamid.inbound", Instant.now()))
                .andWaitForEventOfType(ConversationMemoryProjectionRefreshRequested.class)
                .toArriveAndVerify(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.key().tenantId()).isEqualTo(tenantId);
                    org.assertj.core.api.Assertions.assertThat(event.key().conversationId()).isEqualTo(conversationId);
                    org.assertj.core.api.Assertions.assertThat(event.update().summary()).contains("¿Hay Ranger?");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        private static final ConversationRepository repository = org.mockito.Mockito.mock(ConversationRepository.class);
        private static final ConversationAssistant assistant = org.mockito.Mockito.mock(ConversationAssistant.class);

        @Bean
        @org.springframework.context.annotation.Primary
        ConversationRepository conversationRepository() {
            return repository;
        }

        @Bean
        ConversationAssistant conversationAssistant() {
            return assistant;
        }

        @Bean
        OutboxStore outboxStore() {
            return org.mockito.Mockito.mock(OutboxStore.class);
        }

        @Bean(name = "conversationWorkflow")
        ConversationWorkflow conversationWorkflow(ApplicationEventPublisher eventPublisher) {
            return new ConversationWorkflow(repository, assistant, eventPublisher);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
