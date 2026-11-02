package com.ford.service.followups.adapter.out;

import com.ford.service.followups.application.LeadFollowUpRepository;
import com.ford.service.followups.domain.LeadFollowUp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLeadFollowUpRepository implements LeadFollowUpRepository {

    private final JdbcClient jdbc;

    public JdbcLeadFollowUpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeadFollowUp> find(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                        select id, tenant_id, conversation_id, status, offered_at, consent_at,
                               source_message_key
                        from lead_follow_up
                        where tenant_id = :tenantId and conversation_id = :conversationId
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new LeadFollowUp(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        LeadFollowUp.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("offered_at").toInstant(),
                        instant(rs.getTimestamp("consent_at")),
                        rs.getString("source_message_key")))
                .optional();
    }

    @Override
    public void offer(UUID tenantId, UUID conversationId, String sourceMessageKey) {
        jdbc.sql("""
                insert into lead_follow_up
                    (id, tenant_id, conversation_id, status, offered_at, source_message_key)
                values (:id, :tenantId, :conversationId, 'OFFERED', :now, :sourceMessageKey)
                on conflict (tenant_id, conversation_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("now", Timestamp.from(Instant.now()))
                .param("sourceMessageKey", sourceMessageKey)
                .update();
    }

    @Override
    public boolean accept(UUID tenantId, UUID conversationId, String sourceMessageKey) {
        return jdbc.sql("""
                update lead_follow_up
                set status = 'QUEUED', consent_at = :now, consent_message_key = :sourceMessageKey
                where tenant_id = :tenantId and conversation_id = :conversationId and status = 'OFFERED'
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("now", Timestamp.from(Instant.now()))
                .param("sourceMessageKey", sourceMessageKey)
                .update() == 1;
    }

    @Override
    public boolean decline(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                update lead_follow_up
                set status = 'DECLINED'
                where tenant_id = :tenantId and conversation_id = :conversationId and status = 'OFFERED'
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .update() == 1;
    }

    @Override
    public boolean claim(UUID tenantId, UUID followUpId) {
        return jdbc.sql("""
                update lead_follow_up
                set status = 'CLAIMED'
                where tenant_id = :tenantId and id = :followUpId and status = 'QUEUED'
                """)
                .param("tenantId", tenantId)
                .param("followUpId", followUpId)
                .update() == 1;
    }

    @Override
    public List<LeadFollowUp> queued(UUID tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, conversation_id, status, offered_at, consent_at,
                               source_message_key
                        from lead_follow_up
                        where tenant_id = :tenantId and status = 'QUEUED'
                        order by consent_at, offered_at, id
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new LeadFollowUp(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        LeadFollowUp.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("offered_at").toInstant(),
                        instant(rs.getTimestamp("consent_at")),
                        rs.getString("source_message_key")))
                .list();
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
