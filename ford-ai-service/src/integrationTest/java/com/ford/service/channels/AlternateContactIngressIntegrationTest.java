package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.JdbcInboundMessageDeduplicationAdapter;
import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.handoffcontact.api.StagingContext;
import com.ford.service.handoffcontact.application.AlternateContactCapturePolicy;
import com.ford.service.handoffcontact.application.AlternateContactCryptoProperties;
import com.ford.service.handoffcontact.application.AlternateContactIntakeService;
import com.ford.service.handoffcontact.application.AlternateContactRedactor;
import com.ford.service.handoffcontact.application.AlternateContactStagingCipher;
import com.ford.service.handoffcontact.adapter.out.JdbcAlternateContactStagingRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AlternateContactIngressIntegrationTest {
    private static final String RAW_CONTACT = "55 1234 5678";
    private static final String MESSAGE = "Autorizo compartir mi número con Ford: " + RAW_CONTACT;
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    static JdbcClient jdbc;
    static ChannelMessageService service;
    static AlternateContactStagingCipher cipher;

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        var mapper = new ObjectMapper().findAndRegisterModules();
        cipher = new AlternateContactStagingCipher(
                new AlternateContactCryptoProperties(KEY, 1, null, 0), mapper);
        var staging = new JdbcAlternateContactStagingRepository(jdbc);
        var intake = new AlternateContactIntakeService(
                new AlternateContactCapturePolicy(new AlternateContactRedactor()), cipher, staging, true);
        service = new ChannelMessageService(new JdbcInboundMessageDeduplicationAdapter(jdbc),
                new JdbcInboundMessagePersistenceAdapter(jdbc, mapper), ignored -> { }, intake);
    }

    @Test
    void persistsOnlyRedactedTranscriptAndStoresAcceptedContactAsCiphertext() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receivedAt = Instant.parse("2026-10-23T19:00:00Z");
        var message = new InboundMessage(tenant, Channel.WHATSAPP, "account-1", "provider-" + UUID.randomUUID(),
                "participant-1", MESSAGE, List.of(), receivedAt, "meta:whatsapp:opaque", UUID.randomUUID(), conversation);

        var accepted = service.acceptAll(List.of(message)).getFirst();
        var row = jdbc.sql("select id, conversation_id, content from channel_message where provider_message_id=:id")
                .param("id", message.providerMessageId())
                .query((rs, rowNum) -> new String[]{rs.getObject("id", UUID.class).toString(),
                        rs.getObject("conversation_id", UUID.class).toString(), rs.getString("content")}).single();
        var transcript = jdbc.sql("select content from conversation_message where provider_message_id=:id and direction='INBOUND'")
                .param("id", message.providerMessageId()).query(String.class).single();
        var receiptId = UUID.fromString(row[0]);
        var context = new StagingContext(tenant, UUID.fromString(row[1]), receiptId);
        var encrypted = jdbc.sql("select ciphertext, nonce, key_version from handoff_contact_staging where tenant_id=:tenant and receipt_id=:receipt")
                .param("tenant", tenant).param("receipt", receiptId)
                .query((rs, rowNum) -> new CipherRow(rs.getBytes("ciphertext"), rs.getBytes("nonce"),
                        rs.getInt("key_version"))).single();

        assertThat(accepted.text()).doesNotContain(RAW_CONTACT);
        assertThat(row[2]).doesNotContain(RAW_CONTACT).contains("[dato de contacto omitido]");
        assertThat(transcript).doesNotContain(RAW_CONTACT).contains("[dato de contacto omitido]");
        assertThat(containsSubsequence(encrypted.ciphertext(),
                RAW_CONTACT.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(cipher.decrypt(new com.ford.service.handoffcontact.api.EncryptedContactEnvelope(
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), receivedAt), context))
                .singleElement().extracting(com.ford.service.handoffcontact.api.AlternateContactValue::normalizedValue)
                .isEqualTo("+525512345678");
    }

    private record CipherRow(byte[] ciphertext, byte[] nonce, int keyVersion) { }

    private static boolean containsSubsequence(byte[] value, byte[] candidate) {
        for (int start = 0; start <= value.length - candidate.length; start++) {
            var matches = true;
            for (int offset = 0; offset < candidate.length; offset++) {
                if (value[start + offset] != candidate[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }
}
