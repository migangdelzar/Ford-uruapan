package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcConversationTurnLeaseAdapter implements ConversationTurnLeasePort {

    private final JdbcClient jdbc;

    public JdbcConversationTurnLeaseAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(UUID tenantId, UUID conversationId, String owner, Duration duration) {
        var now = Instant.now();
        return jdbc.sql("""
                insert into conversation_turn_lease
                    (tenant_id, conversation_id, owner_id, lease_until, updated_at)
                values (:tenantId, :conversationId, :ownerId, :leaseUntil, :updatedAt)
                on conflict (tenant_id, conversation_id) do update
                    set owner_id = excluded.owner_id,
                        lease_until = excluded.lease_until,
                        updated_at = excluded.updated_at
                    where conversation_turn_lease.lease_until <= :now
                       or conversation_turn_lease.owner_id = :ownerId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("ownerId", owner)
                .param("leaseUntil", Timestamp.from(now.plus(duration)))
                .param("updatedAt", Timestamp.from(now))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public void release(UUID tenantId, UUID conversationId, String owner) {
        jdbc.sql("""
                delete from conversation_turn_lease
                where tenant_id = :tenantId and conversation_id = :conversationId and owner_id = :ownerId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("ownerId", owner)
                .update();
    }
}
