package com.ford.service.handoffcontact.adapter.out;

import com.ford.service.handoffcontact.application.AlternateContactLifecycleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAlternateContactLifecycleRepository implements AlternateContactLifecycleRepository {
    private static final Duration CONTACT_RETENTION = Duration.ofDays(120);
    private final JdbcClient jdbc;

    public JdbcAlternateContactLifecycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TicketContactReference> findExpiredTicketContacts(Instant now, int limit) {
        return jdbc.sql("""
                with retained_packet as (
                    select tenant_id, id as ticket_id, conversation_id,
                           coalesce((packet->>'alternateContactConsentedAt')::timestamptz, created_at) as collected_at,
                           closed_at
                    from handoff_ticket
                    where jsonb_exists(packet, 'alternateContactValue')
                       or jsonb_exists(packet, 'preferredContactChannel')
                ), expiry as (
                    select tenant_id, ticket_id, conversation_id, collected_at, closed_at,
                           case when closed_at is not null
                                     and closed_at <= collected_at + interval '120 days'
                                then closed_at + interval '120 days'
                                else collected_at + interval '120 days'
                           end as expires_at
                    from retained_packet
                )
                select tenant_id, ticket_id, conversation_id, collected_at, closed_at, expires_at
                from expiry
                where expires_at <= :now
                order by expires_at limit :limit
                """)
                .param("now", Timestamp.from(now)).param("limit", Math.max(1, Math.min(limit, 1000)))
                .query((rs, rowNum) -> new TicketContactReference(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getTimestamp("collected_at").toInstant(), instant(rs, "closed_at"),
                        rs.getTimestamp("expires_at").toInstant())).list();
    }

    @Override
    public List<TicketContactReference> findTicketContacts(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select tenant_id, id as ticket_id, conversation_id,
                       coalesce((packet->>'alternateContactConsentedAt')::timestamptz, created_at) as collected_at,
                       closed_at
                from handoff_ticket
                where tenant_id=:tenantId and conversation_id=:conversationId
                  and (jsonb_exists(packet, 'alternateContactValue')
                       or jsonb_exists(packet, 'preferredContactChannel'))
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, rowNum) -> {
                    var collected = rs.getTimestamp("collected_at").toInstant();
                    var closed = instant(rs, "closed_at");
                    var collectionDeadline = collected.plus(CONTACT_RETENTION);
                    var expiresAt = closed != null && !closed.isAfter(collectionDeadline)
                            ? closed.plus(CONTACT_RETENTION) : collectionDeadline;
                    return new TicketContactReference(tenantId, rs.getObject("ticket_id", UUID.class), conversationId,
                            collected, closed, expiresAt);
                }).list();
    }

    @Override
    public List<StagingContactReference> findStagingContacts(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select tenant_id, conversation_id, receipt_id, expires_at
                from handoff_contact_staging where tenant_id=:tenantId and conversation_id=:conversationId
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, rowNum) -> new StagingContactReference(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class), rs.getObject("receipt_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant())).list();
    }

    @Override
    @Transactional
    public void fenceConversation(UUID tenantId, UUID conversationId, Instant forgottenAt) {
        jdbc.sql("select id from conversation where tenant_id=:tenantId and id=:conversationId for update")
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(UUID.class).optional();
        jdbc.sql("""
                insert into handoff_contact_forget_fence (tenant_id, conversation_id, forgotten_at)
                values (:tenantId, :conversationId, :forgottenAt)
                on conflict (tenant_id, conversation_id) do update
                    set forgotten_at=greatest(handoff_contact_forget_fence.forgotten_at, excluded.forgotten_at)
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("forgottenAt", Timestamp.from(forgottenAt)).update();
    }

    @Override
    public java.util.Optional<Instant> findContactExposure(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select captured_at from handoff_contact_exposure
                where tenant_id=:tenantId and conversation_id=:conversationId
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    @Override
    @Transactional
    public void clearContactExposure(UUID tenantId, UUID conversationId, Instant expectedCapturedAt) {
        jdbc.sql("""
                delete from handoff_contact_exposure
                where tenant_id=:tenantId and conversation_id=:conversationId and captured_at=:capturedAt
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("capturedAt", Timestamp.from(expectedCapturedAt)).update();
    }

    @Override
    public List<ContactExposureReference> findExpiredContactExposures(Instant now, int limit) {
        return jdbc.sql("""
                select tenant_id, conversation_id, captured_at
                from handoff_contact_exposure
                where captured_at <= :retentionCutoff
                order by captured_at limit :limit
                """)
                .param("retentionCutoff", Timestamp.from(now.minus(CONTACT_RETENTION)))
                .param("limit", Math.max(1, Math.min(limit, 1000)))
                .query((rs, rowNum) -> new ContactExposureReference(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class), rs.getTimestamp("captured_at").toInstant()))
                .list();
    }

    @Override
    @Transactional
    public boolean clearTicketContact(UUID tenantId, UUID ticketId) {
        return jdbc.sql("""
                update handoff_ticket
                   set packet = packet - 'alternateContactType' - 'alternateContactValue'
                        - 'alternateContactConsentedAt' - 'alternateContactMessageId'
                        - 'alternateContactPolicyVersion' - 'preferredContactChannel'
                 where tenant_id=:tenantId and id=:ticketId
                   and (jsonb_exists(packet, 'alternateContactValue')
                        or jsonb_exists(packet, 'preferredContactChannel'))
                """)
                .param("tenantId", tenantId).param("ticketId", ticketId).update() == 1;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
