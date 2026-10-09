package com.ford.service.channels;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import liquibase.integration.spring.SpringLiquibase;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.modulith.runtime.verification-enabled=false",
        "ford.conversations.workflow.graph-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MetaInboundWorkflowIntegrationTest.PublicationProbe.class)
class MetaInboundWorkflowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    @org.junit.jupiter.api.BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    private static final String APP_SECRET = "meta-app-secret";
    private static final String PROVIDER_ACCOUNT_ID = "phone-number-1";
    private static final String BODY = """
            {"entry":[{"id":"whatsapp-account-1","changes":[{"value":{"metadata":{"phone_number_id":"phone-number-1"},"messages":[{"id":"wamid.task-2","from":"5214521234567","text":{"body":"Hola"}}]}}]}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ConversationAssistant assistant;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ford.channels.meta.app-secret", () -> APP_SECRET);
    }

    @BeforeEach
    void stubAssistant() {
        when(assistant.respond(any())).thenReturn(new AssistantReply("Test reply", false));
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from channel_message").update();
        jdbc.sql("delete from conversation_message").update();
        jdbc.sql("delete from channel_delivery").update();
        jdbc.sql("delete from conversation").update();
        jdbc.sql("delete from channel_binding").update();
        PublicationProbe.ACCEPTED.set(0);
    }

    @Test
    void acceptsValidBindingPersistsIdentityAndPublishesOneDurableEvent() throws Exception {
        var tenantId = UUID.randomUUID();
        insertBinding(tenantId);
        var publicationsBefore = publicationCount();

        postWebhook(BODY, APP_SECRET)
                .andExpect(status().isAccepted());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from channel_message")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from conversation")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select distinct tenant_id from channel_delivery")
                .query(UUID.class).single()).isEqualTo(tenantId);
        awaitPublication();
        awaitConversationWorkflow();
        assertThat(PublicationProbe.ACCEPTED.get()).isEqualTo(1);
        assertThat(publicationCount()).isGreaterThan(publicationsBefore);
    }

    @Test
    void rejectsForgedTenantHeaderAndDoesNotPersist() throws Exception {
        insertBinding(UUID.randomUUID());

        mockMvc.perform(post("/webhooks/meta/whatsapp")
                .header("X-Tenant-Id", UUID.randomUUID())
                .header("X-Hub-Signature-256", signature(BODY, APP_SECRET))
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsInvalidSignatureAndProviderAccountBeforePersistence() throws Exception {
        insertBinding(UUID.randomUUID());

        mockMvc.perform(post("/webhooks/meta/whatsapp")
                        .header("X-Hub-Signature-256", signature(BODY, "wrong-secret"))
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        var wrongAccountBody = BODY.replace(PROVIDER_ACCOUNT_ID, "another-account");
        mockMvc.perform(post("/webhooks/meta/whatsapp")
                        .header("X-Hub-Signature-256", signature(wrongAccountBody, APP_SECRET))
                        .contentType(APPLICATION_JSON)
                        .content(wrongAccountBody))
                .andExpect(status().isForbidden());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void acknowledgesReplayWithoutPublishingSecondEvent() throws Exception {
        insertBinding(UUID.randomUUID());

        postWebhook(BODY, APP_SECRET).andExpect(status().isAccepted());
        awaitPublication();
        awaitConversationWorkflow();
        var publicationsAfterFirstDelivery = publicationCount();
        postWebhook(BODY, APP_SECRET).andExpect(status().isAccepted());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from channel_message")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(PublicationProbe.ACCEPTED.get()).isEqualTo(1);
        assertThat(publicationCount()).isEqualTo(publicationsAfterFirstDelivery);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(
            String body, String secret) throws Exception {
        return mockMvc.perform(post("/webhooks/meta/whatsapp")
                .header("X-Hub-Signature-256", signature(body, secret))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private void insertBinding(UUID tenantId) {
        var bindingId = UUID.randomUUID();
        jdbc.sql("""
                insert into channel_binding
                    (id, webhook_key_hash, tenant_id, channel, provider_account_id,
                     secret_reference, enabled, created_at, updated_at)
                values (:id, null, :tenantId, 'WHATSAPP', :providerAccountId,
                        'meta-app-secret-reference', true, current_timestamp, current_timestamp)
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("providerAccountId", PROVIDER_ACCOUNT_ID)
                .update();
    }

    private void awaitPublication() throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (PublicationProbe.ACCEPTED.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    private void awaitConversationWorkflow() throws InterruptedException {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            var pending = jdbc.sql("select count(*) from event_publication "
                            + "where event_type like '%InboundMessageAccepted%' "
                            + "and listener_id like '%ConversationWorkflow.on%' and completion_date is null")
                    .query(Integer.class).single();
            if (pending == 0) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(jdbc.sql("select count(*) from event_publication "
                        + "where event_type like '%InboundMessageAccepted%' "
                        + "and listener_id like '%ConversationWorkflow.on%' and completion_date is null")
                .query(Integer.class).single()).isZero();
    }

    private int publicationCount() {
        return jdbc.sql("select count(*) from event_publication")
                .query(Integer.class).single();
    }

    private static String signature(String body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(
                mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class PublicationProbe {
        static final AtomicInteger ACCEPTED = new AtomicInteger();

        @ApplicationModuleListener
        void on(InboundMessageAccepted event) {
            ACCEPTED.incrementAndGet();
        }
    }
}
