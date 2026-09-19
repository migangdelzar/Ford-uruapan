package com.ford.service.channels;

import com.ford.service.channels.api.InboundMessageAccepted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
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
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MetaInboundWorkflowIntegrationTest.PublicationProbe.class)
class MetaInboundWorkflowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @org.junit.jupiter.api.BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    private static final String WEBHOOK_KEY = "opaque-whatsapp-key";
    private static final String SECRET = "binding-secret";
    private static final String BODY = """
            {"entry":[{"id":"whatsapp-account-1","changes":[{"value":{"messages":[{"id":"wamid.task-2","from":"5214521234567","text":{"body":"Hola"}}]}}]}]}
            """;

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
        jdbc.sql("delete from channel_message").update();
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

        postWebhook(WEBHOOK_KEY, BODY, SECRET)
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
        assertThat(PublicationProbe.ACCEPTED.get()).isEqualTo(1);
        assertThat(publicationCount()).isEqualTo(publicationsBefore + 1);
    }

    @Test
    void rejectsForgedTenantHeaderAndDoesNotPersist() throws Exception {
        insertBinding(UUID.randomUUID());

        mockMvc.perform(post("/webhooks/meta/whatsapp/" + WEBHOOK_KEY)
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .header("X-Hub-Signature-256", signature(BODY, SECRET))
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsInvalidSignatureAndProviderAccountBeforePersistence() throws Exception {
        insertBinding(UUID.randomUUID());

        mockMvc.perform(post("/webhooks/meta/whatsapp/" + WEBHOOK_KEY)
                        .header("X-Hub-Signature-256", signature(BODY, "wrong-secret"))
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        var wrongAccountBody = BODY.replace("whatsapp-account-1", "another-account");
        mockMvc.perform(post("/webhooks/meta/whatsapp/" + WEBHOOK_KEY)
                        .header("X-Hub-Signature-256", signature(wrongAccountBody, SECRET))
                        .contentType(APPLICATION_JSON)
                        .content(wrongAccountBody))
                .andExpect(status().isForbidden());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void acknowledgesReplayWithoutPublishingSecondEvent() throws Exception {
        insertBinding(UUID.randomUUID());
        var publicationsBefore = publicationCount();

        postWebhook(WEBHOOK_KEY, BODY, SECRET).andExpect(status().isAccepted());
        awaitPublication();
        postWebhook(WEBHOOK_KEY, BODY, SECRET).andExpect(status().isAccepted());

        assertThat(jdbc.sql("select count(*) from channel_delivery")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from channel_message")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(PublicationProbe.ACCEPTED.get()).isEqualTo(1);
        assertThat(publicationCount()).isEqualTo(publicationsBefore + 1);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(
            String webhookKey, String body, String secret) throws Exception {
        return mockMvc.perform(post("/webhooks/meta/whatsapp/" + webhookKey)
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
                values (:id, :keyHash, :tenantId, 'WHATSAPP', 'whatsapp-account-1',
                        :secret, true, current_timestamp, current_timestamp)
                """)
                .param("id", bindingId)
                .param("keyHash", sha256(WEBHOOK_KEY))
                .param("tenantId", tenantId)
                .param("secret", SECRET)
                .update();
    }

    private void awaitPublication() throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (PublicationProbe.ACCEPTED.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
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

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
