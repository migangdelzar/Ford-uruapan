package com.ford.service.handoffcontact.adapter.out;

import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.api.EncryptedContactEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAlternateContactStagingRepository implements AlternateContactStagingPort {
    private final JdbcClient jdbc;

    public JdbcAlternateContactStagingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(UUID tenantId, UUID conversationId, UUID receiptId, EncryptedContactEnvelope envelope,
                     Instant collectedAt, Instant expiresAt) {
        var conversationExists = jdbc.sql("select id from conversation where tenant_id=:tenantId and id=:conversationId for update")
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(UUID.class).optional().isPresent();
        if (!conversationExists) return;
        var forgottenAt = jdbc.sql("""
                select forgotten_at from handoff_contact_forget_fence
                where tenant_id=:tenantId and conversation_id=:conversationId
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        if (forgottenAt != null && !collectedAt.isAfter(forgottenAt)) return;
        jdbc.sql("""
                insert into handoff_contact_staging
                    (tenant_id, conversation_id, receipt_id, ciphertext, nonce, key_version, collected_at, expires_at)
                values (:tenantId, :conversationId, :receiptId, :ciphertext, :nonce, :keyVersion, :collectedAt, :expiresAt)
                on conflict (tenant_id, conversation_id, receipt_id) do update
                set ciphertext = excluded.ciphertext, nonce = excluded.nonce,
                    key_version = excluded.key_version, collected_at = excluded.collected_at,
                    expires_at = excluded.expires_at
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("receiptId", receiptId)
                .param("ciphertext", envelope.ciphertext())
                .param("nonce", envelope.nonce())
                .param("keyVersion", envelope.keyVersion())
                .param("collectedAt", Timestamp.from(collectedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        jdbc.sql("""
                insert into handoff_contact_exposure (tenant_id, conversation_id, captured_at)
                values (:tenantId, :conversationId, :capturedAt)
                on conflict (tenant_id, conversation_id) do update
                    set captured_at=greatest(handoff_contact_exposure.captured_at, excluded.captured_at)
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("capturedAt", Timestamp.from(collectedAt)).update();
    }

    @Override
    public Optional<EncryptedContactEnvelope> find(UUID tenantId, UUID conversationId, UUID receiptId, Instant now) {
        return jdbc.sql("""
                select ciphertext, nonce, key_version, collected_at
                from handoff_contact_staging
                where tenant_id=:tenantId and conversation_id=:conversationId and receipt_id=:receiptId
                  and expires_at > :now
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("receiptId", receiptId)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new EncryptedContactEnvelope(rs.getBytes("ciphertext"),
                        rs.getBytes("nonce"), rs.getInt("key_version"),
                        rs.getTimestamp("collected_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, UUID conversationId, UUID receiptId) {
        return jdbc.sql("delete from handoff_contact_staging where tenant_id=:tenantId and conversation_id=:conversationId and receipt_id=:receiptId")
                .param("tenantId", tenantId).param("conversationId", conversationId).param("receiptId", receiptId)
                .update() > 0;
    }

    @Override
    public List<ExpiredStagingReference> findExpired(Instant now, int limit) {
        return jdbc.sql("""
                select tenant_id, conversation_id, receipt_id, expires_at
                from handoff_contact_staging where expires_at <= :now
                order by expires_at limit :limit
                """)
                .param("now", Timestamp.from(now)).param("limit", Math.max(1, Math.min(limit, 1000)))
                .query((rs, rowNum) -> new ExpiredStagingReference(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class), rs.getObject("receipt_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant())).list();
    }
}
