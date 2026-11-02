package com.ford.service.handoffcontact;

import com.ford.service.database.FordPostgresContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.adapter.out.JdbcConversationRepository;
import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.handoffcontact.adapter.out.DefaultAlternateContactForgetAdapter;
import com.ford.service.handoffcontact.adapter.out.JdbcAlternateContactLifecycleRepository;
import com.ford.service.handoffcontact.adapter.out.JdbcAlternateContactStagingRepository;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.EncryptedContactEnvelope;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AlternateContactRetentionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    static JdbcClient jdbc;
    static JdbcAlternateContactLifecycleRepository lifecycle;

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        lifecycle = new JdbcAlternateContactLifecycleRepository(jdbc);
    }

    @Test
    void lateClosedTicketKeepsOpenDeadlineButEarlyClosedTicketGets120DaysAfterClosure() {
        var tenantId = UUID.randomUUID();
        var collectedAt = Instant.parse("2025-01-01T00:00:00Z");
        var lateConversation = UUID.randomUUID();
        var lateTicket = UUID.randomUUID();
        var lateClose = collectedAt.plus(150, ChronoUnit.DAYS);
        insertContactTicket(tenantId, lateConversation, lateTicket, collectedAt, lateClose);

        assertThat(lifecycle.findExpiredTicketContacts(collectedAt.plus(119, ChronoUnit.DAYS), 100)).isEmpty();
        assertThat(lifecycle.findExpiredTicketContacts(collectedAt.plus(121, ChronoUnit.DAYS), 100))
                .anySatisfy(row -> {
                    assertThat(row.ticketId()).isEqualTo(lateTicket);
                    assertThat(row.expiresAt()).isEqualTo(collectedAt.plus(120, ChronoUnit.DAYS));
                });

        var earlyConversation = UUID.randomUUID();
        var earlyTicket = UUID.randomUUID();
        var earlyClose = collectedAt.plus(90, ChronoUnit.DAYS);
        insertContactTicket(tenantId, earlyConversation, earlyTicket, collectedAt, earlyClose);
        assertThat(lifecycle.findExpiredTicketContacts(earlyClose.plus(121, ChronoUnit.DAYS), 100))
                .anySatisfy(row -> {
                    assertThat(row.ticketId()).isEqualTo(earlyTicket);
                    assertThat(row.expiresAt()).isEqualTo(earlyClose.plus(120, ChronoUnit.DAYS));
                });
    }

    @Test
    void preferenceOnlyPacketIsFoundAndClearedByRetentionAndForgetLookups() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var createdAt = Instant.parse("2025-01-01T00:00:00Z");
        insertTicket(tenantId, conversationId, ticketId, createdAt, null,
                "{\"preferredContactChannel\":\"WHATSAPP\"}");

        assertThat(lifecycle.findExpiredTicketContacts(createdAt.plus(121, ChronoUnit.DAYS), 100))
                .anySatisfy(row -> {
                    assertThat(row.ticketId()).isEqualTo(ticketId);
                    assertThat(row.expiresAt()).isEqualTo(createdAt.plus(120, ChronoUnit.DAYS));
                });
        assertThat(lifecycle.findTicketContacts(tenantId, conversationId))
                .anySatisfy(row -> assertThat(row.ticketId()).isEqualTo(ticketId));
        assertThat(lifecycle.clearTicketContact(tenantId, ticketId)).isTrue();
        assertThat(jdbc.sql("select packet from handoff_ticket where id=:id")
                .param("id", ticketId).query(String.class).single())
                .doesNotContain("preferredContactChannel");
    }

    @Test
    void forgetFencePreventsStaleInFlightContactFromEnteringLaterHandoff() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var forgottenAt = Instant.parse("2026-10-23T20:00:00Z");
        insertConversation(tenantId, conversationId, forgottenAt.minusSeconds(60));
        var markers = new ArrayList<AlternateContactDeletionLedger.DeletionMarker>();
        var staging = new JdbcAlternateContactStagingRepository(jdbc);
        var forget = new DefaultAlternateContactForgetAdapter(lifecycle,
                staging, markers::add);

        var originalReceiptId = UUID.randomUUID();
        var originalCollectedAt = forgottenAt.minusSeconds(10);
        staging.save(tenantId, conversationId, originalReceiptId,
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, originalCollectedAt),
                originalCollectedAt, originalCollectedAt.plusSeconds(3600));
        forget.forgetForConversation(tenantId, conversationId, forgottenAt, "synthetic-operator");
        var receiptId = UUID.randomUUID();
        var collectedAt = forgottenAt.minusSeconds(1);
        staging.save(tenantId, conversationId, receiptId,
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, collectedAt),
                collectedAt, collectedAt.plusSeconds(3600));
        assertThat(staging.find(tenantId, conversationId, receiptId, collectedAt.plusSeconds(5))).isEmpty();
        var freshReceiptId = UUID.randomUUID();
        var freshCollectedAt = forgottenAt.plusSeconds(1);
        staging.save(tenantId, conversationId, freshReceiptId,
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, freshCollectedAt),
                freshCollectedAt, freshCollectedAt.plusSeconds(3600));
        assertThat(staging.find(tenantId, conversationId, freshReceiptId, freshCollectedAt.plusSeconds(5))).isPresent();
        var secondForgottenAt = forgottenAt.plusSeconds(2);
        forget.forgetForConversation(tenantId, conversationId, secondForgottenAt, "synthetic-operator");
        assertThat(staging.find(tenantId, conversationId, freshReceiptId, secondForgottenAt.plusSeconds(1))).isEmpty();

        var staleContact = new AlternateContactValue(AlternateContactValue.ContactType.EMAIL,
                "private@example.com", freshCollectedAt, "synthetic-receipt", "policy-v1");
        var packet = BdcHandoffPacket.empty().withAlternateContact(staleContact);
        var repository = new JdbcConversationRepository(jdbc, new ObjectMapper());
        assertThat(repository.requestHumanHandoff(tenantId, conversationId, 0,
                new HandoffDecision("CUSTOMER_REQUEST", "NORMAL", "forget-race-test", packet))).isTrue();

        var persistedPacket = jdbc.sql("select packet::text from handoff_ticket where conversation_id=:conversation")
                .param("conversation", conversationId).query(String.class).single();
        assertThat(persistedPacket).doesNotContain("private@example.com", "alternateContactValue",
                "preferredContactChannel");
        assertThat(markers).anySatisfy(marker -> {
            assertThat(marker.conversationId()).isEqualTo(conversationId);
            assertThat(marker.ticketId()).isNull();
            assertThat(marker.receiptId()).isNull();
            assertThat(marker.deletedAt()).isEqualTo(secondForgottenAt);
        });
    }

    @Test
    void forgetWritesMarkerAfterStagingWasConsumedAndLiveRowsAreGone() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        insertConversation(tenantId, conversationId, Instant.parse("2026-10-23T19:00:00Z"));
        var staging = new JdbcAlternateContactStagingRepository(jdbc);
        var capturedAt = Instant.parse("2026-10-23T19:01:00Z");
        var receiptId = UUID.randomUUID();
        staging.save(tenantId, conversationId, receiptId,
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, capturedAt),
                capturedAt, capturedAt.plusSeconds(3600));
        staging.delete(tenantId, conversationId, receiptId);
        var markers = new java.util.ArrayList<com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionMarker>();
        var forget = new com.ford.service.handoffcontact.adapter.out.DefaultAlternateContactForgetAdapter(
                lifecycle, staging, markers::add);

        forget.forgetForConversation(tenantId, conversationId, capturedAt.plusSeconds(60), "synthetic-operator");

        assertThat(markers).anySatisfy(marker -> {
            assertThat(marker.conversationId()).isEqualTo(conversationId);
            assertThat(marker.ticketId()).isNull();
            assertThat(marker.receiptId()).isNull();
        });
    }

    @Test
    void contactExposureMetadataExpiresAfterCollectionRetentionWindow() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var capturedAt = Instant.parse("2026-06-01T00:00:00Z");
        var now = Instant.parse("2026-10-01T00:00:00Z");
        insertConversation(tenantId, conversationId, capturedAt.minusSeconds(60));
        var staging = new JdbcAlternateContactStagingRepository(jdbc);
        staging.save(tenantId, conversationId, UUID.randomUUID(),
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, capturedAt),
                capturedAt, capturedAt.plusSeconds(3600));

        assertThat(lifecycle.findExpiredContactExposures(now, 100))
                .anySatisfy(exposure -> {
                    assertThat(exposure.tenantId()).isEqualTo(tenantId);
                    assertThat(exposure.conversationId()).isEqualTo(conversationId);
                    assertThat(exposure.capturedAt()).isEqualTo(capturedAt);
                });
        assertThat(lifecycle.findExpiredContactExposures(capturedAt.plusSeconds(120L * 24 * 60 * 60 - 1), 100))
                .noneMatch(exposure -> exposure.conversationId().equals(conversationId));
    }

    @Test
    void staleExpiryReferenceCannotClearFreshlyAdvancedExposure() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var expiredCapture = Instant.parse("2026-01-01T00:00:00Z");
        var freshCapture = expiredCapture.plusSeconds(120L * 24 * 60 * 60 + 1);
        insertConversation(tenantId, conversationId, expiredCapture.minusSeconds(60));
        var staging = new JdbcAlternateContactStagingRepository(jdbc);
        staging.save(tenantId, conversationId, UUID.randomUUID(),
                new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 1, expiredCapture),
                expiredCapture, expiredCapture.plusSeconds(3600));
        var staleReference = lifecycle.findExpiredContactExposures(
                expiredCapture.plusSeconds(120L * 24 * 60 * 60), 100).stream()
                .filter(exposure -> exposure.conversationId().equals(conversationId)).findFirst().orElseThrow();
        staging.save(tenantId, conversationId, UUID.randomUUID(),
                new EncryptedContactEnvelope(new byte[]{2}, new byte[12], 1, freshCapture),
                freshCapture, freshCapture.plusSeconds(3600));

        lifecycle.clearContactExposure(tenantId, conversationId, staleReference.capturedAt());

        assertThat(lifecycle.findContactExposure(tenantId, conversationId)).contains(freshCapture);
    }

    private static void insertConversation(UUID tenantId, UUID conversationId, Instant createdAt) {
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenant, 'WHATSAPP', :providerConversation, 'BOT_ACTIVE', 0, :created, :updated,
                        :providerAccount, :participant)
                """)
                .param("id", conversationId).param("tenant", tenantId)
                .param("providerConversation", conversationId.toString())
                .param("providerAccount", "forget-account-" + conversationId)
                .param("participant", "forget-participant-" + conversationId)
                .param("created", Timestamp.from(createdAt)).param("updated", Timestamp.from(createdAt)).update();
    }

    private static void insertContactTicket(UUID tenantId, UUID conversationId, UUID ticketId,
                                            Instant collectedAt, Instant closedAt) {
        var packet = """
                {"alternateContactType":"EMAIL","alternateContactValue":"private@example.com",
                 "alternateContactConsentedAt":"%s","alternateContactMessageId":"receipt",
                 "alternateContactPolicyVersion":"v1","preferredContactChannel":"ALTERNATE_EMAIL"}
                """.formatted(collectedAt);
        insertTicket(tenantId, conversationId, ticketId, collectedAt, closedAt, packet);
    }

    private static void insertTicket(UUID tenantId, UUID conversationId, UUID ticketId,
                                     Instant createdAt, Instant closedAt, String packet) {
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenant, 'WHATSAPP', :providerConversation, 'BOT_ACTIVE', 0, :created, :updated,
                        :providerAccount, :participant)
                """)
                .param("id", conversationId).param("tenant", tenantId)
                .param("providerConversation", conversationId.toString())
                .param("providerAccount", "retention-account-" + conversationId)
                .param("participant", "retention-participant-" + conversationId)
                .param("created", Timestamp.from(createdAt)).param("updated", Timestamp.from(createdAt)).update();
        jdbc.sql("""
                insert into handoff_ticket
                    (id, tenant_id, conversation_id, status, reason, version, created_at, closed_at, priority,
                     idempotency_key, packet, packet_partial, packet_completeness, internal_note)
                values (:id, :tenant, :conversation, :status, 'CUSTOMER_REQUEST', 1, :created, :closed, 'NORMAL',
                        :idempotency, cast(:packet as jsonb), true, 0, null)
                """)
                .param("id", ticketId).param("tenant", tenantId).param("conversation", conversationId)
                .param("status", closedAt == null ? "HUMAN_PENDING" : "CLOSED")
                .param("created", Timestamp.from(createdAt))
                .param("closed", closedAt == null ? null : Timestamp.from(closedAt))
                .param("idempotency", "retention-test-" + ticketId).param("packet", packet).update();
    }
}
