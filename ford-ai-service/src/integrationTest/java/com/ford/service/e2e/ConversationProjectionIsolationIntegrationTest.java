package com.ford.service.e2e;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.Intent;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.modulith.runtime.verification-enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ConversationProjectionIsolationIntegrationTest.TestBeans.class)
class ConversationProjectionIsolationIntegrationTest {

    private static final String APP_SECRET = "projection-test-app-secret";
    private static final String PROVIDER_ACCOUNT_ID = "projection-phone-number-1";
    private static final String BODY = """
            {"entry":[{"id":"whatsapp-account-1","changes":[{"value":{"metadata":{"phone_number_id":"projection-phone-number-1"},"messages":[{"id":"wamid.projection-1","from":"5214521234567","text":{"body":"¿Hay Ranger?"}}]}}]}]}
            """;
    private static final AtomicBoolean FAIL_PROJECTION = new AtomicBoolean();

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
        registry.add("ford.channels.meta.app-secret", () -> APP_SECRET);
    }

    @AfterEach
    void cleanUp() {
        FAIL_PROJECTION.set(false);
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from conversation_message").update();
        jdbc.sql("delete from channel_message").update();
        jdbc.sql("delete from conversation").update();
        jdbc.sql("delete from channel_binding").update();
    }

    @Test
    void keepsAcceptedTurnAndDeliveryWhenProjectionListenerFails() throws Exception {
        FAIL_PROJECTION.set(true);
        var tenantId = UUID.randomUUID();
        insertBinding(tenantId);

        mockMvc.perform(post("/webhooks/meta/whatsapp")
                        .header("X-Hub-Signature-256", signature(BODY, APP_SECRET))
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());

        awaitCount("select count(*) from event_publication "
                + "where event_type like '%ConversationMemoryProjectionRefreshRequested%' "
                + "and completion_date is null", 1);

        var conversationId = jdbc.sql("select id from conversation where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(UUID.class).single();
        assertThat(jdbc.sql("select state from conversation where id = :id")
                .param("id", conversationId).query(String.class).single()).isEqualTo("BOT_ACTIVE");
        assertThat(jdbc.sql("select count(*) from conversation_message "
                        + "where conversation_id = :id and direction = 'OUTBOUND'")
                .param("id", conversationId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event "
                        + "where aggregate_id = :id and event_type = 'OUTBOUND_DELIVERY_REQUESTED'")
                .param("id", conversationId).query(Integer.class).single()).isEqualTo(1);
    }

    private void insertBinding(UUID tenantId) {
        jdbc.sql("""
                insert into channel_binding
                    (id, webhook_key_hash, tenant_id, channel, provider_account_id,
                     secret_reference, enabled, created_at, updated_at)
                values (:id, null, :tenantId, 'WHATSAPP', :providerAccountId,
                        'meta-app-secret-reference', true, current_timestamp, current_timestamp)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("providerAccountId", PROVIDER_ACCOUNT_ID)
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
        assertThat(actual).as("matching projection publication count").isEqualTo(expected);
    }

    private static String signature(String body, String secret) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        AssistantService assistantService() {
            return (context, userText) -> new AssistantTurn(
                    "La Ranger tiene opciones disponibles. Confirmo precio e inventario con la agencia.",
                    Intent.CATALOG, false, List.of("catalog:ranger"), "test", "fixture-projection");
        }

        @Bean
        @Primary
        ConversationMemoryPort failingProjectionMemory(JdbcConversationMemoryRepository durable) {
            return new ConversationMemoryPort() {
                @Override
                public ConversationMemoryContext load(MemoryQuery query) {
                    return durable.load(query);
                }

                @Override
                public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
                    durable.recordInbound(key, message);
                }

                @Override
                public void invalidate(ConversationMemoryKey key) {
                    durable.invalidate(key);
                }

                @Override
                public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                           MemoryProjectionUpdate update,
                                                           long expectedVersion) {
                    if (FAIL_PROJECTION.get()) {
                        throw new IllegalStateException("fixture projection failure");
                    }
                    return durable.updateProjection(key, update, expectedVersion);
                }
            };
        }
    }
}
