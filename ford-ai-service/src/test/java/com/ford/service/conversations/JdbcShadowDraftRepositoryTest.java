package com.ford.service.conversations;

import com.ford.service.conversations.adapter.out.JdbcShadowDraftRepository;
import com.ford.service.conversations.domain.ShadowDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcShadowDraftRepositoryTest {

    private JdbcClient jdbc;
    private JdbcShadowDraftRepository repository;
    private UUID tenantId;
    private UUID conversationId;

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:shadow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists conversation_shadow_draft").update();
        jdbc.sql("""
                create table conversation_shadow_draft (
                    id uuid primary key,
                    tenant_id uuid not null,
                    conversation_id uuid not null,
                    receipt_id uuid not null,
                    inbound_provider_message_id varchar(240) not null,
                    draft_text text not null,
                    review_status varchar(16) not null default 'PENDING',
                    review_note varchar(500) not null default '',
                    reviewed_by uuid,
                    reviewed_at timestamp with time zone,
                    created_at timestamp with time zone not null,
                    expires_at timestamp with time zone not null,
                    unique (tenant_id, conversation_id, inbound_provider_message_id)
                )
                """).update();
        tenantId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        repository = new JdbcShadowDraftRepository(jdbc);
    }

    @Test
    void savesAndListsDraftsScopedByTenantAndConversation() {
        var created = Instant.parse("2026-10-17T12:00:00Z");
        repository.save(draft(UUID.randomUUID(), created, created.plusSeconds(604800), "PENDING"));
        repository.save(draft(UUID.randomUUID(), created.plusSeconds(30), created.plusSeconds(604830), "PENDING"));
        repository.save(new ShadowDraft(UUID.randomUUID(), tenantId, UUID.randomUUID(), UUID.randomUUID(),
                "wamid.other", "otra conversación", created, created.plusSeconds(604800), "PENDING"));

        var drafts = repository.list(tenantId, conversationId, 50);

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).createdAt()).isEqualTo(created.plusSeconds(30));
        assertThat(drafts).allMatch(draft -> draft.conversationId().equals(conversationId));
    }

    @Test
    void reRecordingTheSameInboundIsIdempotent() {
        var created = Instant.parse("2026-10-17T12:00:00Z");
        var id = UUID.randomUUID();
        repository.save(new ShadowDraft(id, tenantId, conversationId, UUID.randomUUID(),
                "wamid.same", "primera versión", created, created.plusSeconds(604800), "PENDING"));
        repository.save(new ShadowDraft(UUID.randomUUID(), tenantId, conversationId, UUID.randomUUID(),
                "wamid.same", "segunda versión", created, created.plusSeconds(604800), "PENDING"));

        assertThat(jdbc.sql("select count(*) from conversation_shadow_draft").query(Integer.class).single()).isEqualTo(1);
        assertThat(repository.list(tenantId, conversationId, 50).get(0).text()).isEqualTo("segunda versión");
    }

    @Test
    void listExcludesExpiredDrafts() {
        var created = Instant.parse("2026-10-17T12:00:00Z");
        repository.save(draft(UUID.randomUUID(), created, created.minusSeconds(1), "PENDING"));

        assertThat(repository.list(tenantId, conversationId, 50)).isEmpty();
    }

    @Test
    void reviewIsSingleUseAndTenantScoped() {
        var id = UUID.randomUUID();
        var created = Instant.parse("2026-10-17T12:00:00Z");
        repository.save(new ShadowDraft(id, tenantId, conversationId, UUID.randomUUID(),
                "wamid.review", "borrador", created, created.plusSeconds(604800), "PENDING"));

        assertThat(repository.review(tenantId, UUID.randomUUID(), id, UUID.randomUUID(), "ACCEPTED", "ok")).isFalse();
        assertThat(repository.review(tenantId, conversationId, id, UUID.randomUUID(), "ACCEPTED", "ok")).isTrue();
        assertThat(repository.review(tenantId, conversationId, id, UUID.randomUUID(), "REJECTED", "unsafe")).isFalse();
        assertThat(repository.list(tenantId, conversationId, 50).get(0).reviewStatus()).isEqualTo("ACCEPTED");
    }

    private ShadowDraft draft(UUID id, Instant created, Instant expires, String status) {
        return new ShadowDraft(id, tenantId, conversationId, UUID.randomUUID(),
                "wamid." + id, "borrador sintético", created, expires, status);
    }
}
