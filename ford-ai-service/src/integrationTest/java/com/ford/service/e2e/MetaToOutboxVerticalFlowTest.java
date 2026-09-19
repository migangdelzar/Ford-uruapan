package com.ford.service.e2e;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.Intent;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.api.AssistantReply;
import org.springframework.context.ApplicationEventPublisher;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MetaToOutboxVerticalFlowTest.TestBeans.class)
class MetaToOutboxVerticalFlowTest {

    private static final String WEBHOOK_KEY = "vertical-whatsapp-key";
    private static final String SECRET = "vertical-binding-secret";
    private static final String BODY = """
            {"entry":[{"id":"whatsapp-account-1","changes":[{"value":{"messages":[{"id":"wamid.vertical-1","from":"5214521234567","text":{"body":"¿Cuál es el precio de la Ranger 2026?"}}]}}]}]}
            """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from conversation_message").update();
        jdbc.sql("delete from channel_message").update();
        jdbc.sql("delete from conversation").update();
        jdbc.sql("delete from channel_binding").update();
    }

    @Test
    void signedMetaQuestionProducesOneGroundedSpanishOutboundAndReplayDoesNotDuplicate() throws Exception {
        var tenantId = UUID.randomUUID();
        insertBinding(tenantId);

        postWebhook()
                .andExpect(status().isAccepted());

        awaitCount("select count(*) from outbox_event where event_type = 'OUTBOUND_DELIVERY_REQUESTED'", 1);

        var conversationId = jdbc.sql("select id from conversation where tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(UUID.class)
                .single();
        var outbound = jdbc.sql("select content from conversation_message where conversation_id = :conversationId and direction = 'OUTBOUND'")
                .param("conversationId", conversationId)
                .query(String.class)
                .single();

        assertThat(outbound)
                .contains("Ranger 2026", "$589,000 MXN", "catalog:ranger-2026", "vigencia")
                .doesNotContain("5214521234567", "Ana", "ana@example.com");
        assertThat(jdbc.sql("select state from conversation where id = :conversationId")
                .param("conversationId", conversationId)
                .query(String.class).single()).isEqualTo("BOT_ACTIVE");
        assertThat(jdbc.sql("select count(*) from channel_message where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(Integer.class).single()).isEqualTo(1);

        postWebhook()
                .andExpect(status().isAccepted());

        awaitCount("select count(*) from outbox_event where event_type = 'OUTBOUND_DELIVERY_REQUESTED'", 1);
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id = :conversationId and direction = 'OUTBOUND'")
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from channel_message where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(Integer.class).single()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook() throws Exception {
        return mockMvc.perform(post("/webhooks/meta/whatsapp/" + WEBHOOK_KEY)
                .header("X-Hub-Signature-256", signature(BODY, SECRET))
                .contentType(APPLICATION_JSON)
                .content(BODY));
    }

    private void insertBinding(UUID tenantId) {
        jdbc.sql("""
                insert into channel_binding
                    (id, webhook_key_hash, tenant_id, channel, provider_account_id,
                     secret_reference, enabled, created_at, updated_at)
                values (:id, :keyHash, :tenantId, 'WHATSAPP', 'whatsapp-account-1',
                        :secret, true, current_timestamp, current_timestamp)
                """)
                .param("id", UUID.randomUUID())
                .param("keyHash", sha256(WEBHOOK_KEY))
                .param("tenantId", tenantId)
                .param("secret", SECRET)
                .update();
    }

    private void awaitCount(String sql, int expected) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        int actual;
        do {
            actual = jdbc.sql(sql).query(Integer.class).single();
            if (actual == expected) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        var conversationCount = jdbc.sql("select count(*) from conversation").query(Integer.class).single();
        var state = conversationCount == 0 ? "none" : jdbc.sql("select state from conversation limit 1")
                .query(String.class).single();
        var inboundPublications = jdbc.sql("select count(*) from event_publication where event_type like '%InboundMessageAccepted%'")
                .query(Integer.class).single();
        assertThat(actual)
                .withFailMessage("outbox=%s conversations=%s state=%s inboundPublications=%s", actual,
                        conversationCount, state, inboundPublications)
                .isEqualTo(expected);
    }

    private static String signature(String body, String secret) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        AssistantService assistantService() {
            return (context, userText) -> new AssistantTurn(
                    "La Ford Ranger 2026 tiene precio vigente desde $589,000 MXN. "
                            + "Fuente: catalog:ranger-2026; vigencia: 2026-09-19. Confirma inventario con la agencia.",
                    Intent.CATALOG, false, java.util.List.of("catalog:ranger-2026"), "test", "fixture-1");
        }

        @Bean
        @Primary
        ConversationAssistant conversationAssistant(AssistantService assistantService) {
            return request -> {
                var turn = assistantService.respond(
                        new com.ford.service.ai.application.AiExecutionContext(
                                request.tenantId(), false, request.conversationId()), request.message());
                return new AssistantReply(turn.text(), turn.requestsHuman());
            };
        }

        @Bean
        ConversationWorkflow testConversationWorkflow(ConversationRepository repository,
                                                       ConversationAssistant assistant,
                                                       ApplicationEventPublisher eventPublisher) {
            return new ConversationWorkflow(repository, assistant, eventPublisher);
        }
    }
}
