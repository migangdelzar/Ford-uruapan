package com.ford.service.leads.adapter.out;

import com.ford.service.leads.application.LeadCommandService.LeadIdempotencyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLeadIdempotencyRepository implements LeadIdempotencyRepository {
    private final JdbcClient jdbc;

    public JdbcLeadIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredCommand> find(UUID tenantId, String actorId, String operation, String key) {
        return jdbc.sql("""
                select request_hash, response_json from operator_idempotency
                 where tenant_id = :tenantId and actor_id = :actorId
                   and operation = :operation and idempotency_key = :key
                """)
                .param("tenantId", tenantId).param("actorId", actorId)
                .param("operation", operation).param("key", key)
                .query((rs, row) -> new StoredCommand(rs.getString("request_hash"), rs.getString("response_json")))
                .optional();
    }

    @Override
    public boolean reserve(UUID tenantId, String actorId, String operation, String key,
                           String requestHash, Instant createdAt) {
        return jdbc.sql("""
                insert into operator_idempotency
                    (id, tenant_id, actor_id, operation, idempotency_key, request_hash, created_at)
                values (:id, :tenantId, :actorId, :operation, :key, :requestHash, :createdAt)
                on conflict (tenant_id, actor_id, operation, idempotency_key) do nothing
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("actorId", actorId)
                .param("operation", operation).param("key", key).param("requestHash", requestHash)
                .param("createdAt", Timestamp.from(createdAt)).update() == 1;
    }

    @Override
    public void complete(UUID tenantId, String actorId, String operation, String key,
                         String responseJson, Instant completedAt) {
        jdbc.sql("""
                update operator_idempotency set response_json = cast(:responseJson as jsonb), completed_at = :completedAt
                 where tenant_id = :tenantId and actor_id = :actorId
                   and operation = :operation and idempotency_key = :key
                """)
                .param("tenantId", tenantId).param("actorId", actorId).param("operation", operation)
                .param("key", key).param("responseJson", responseJson)
                .param("completedAt", Timestamp.from(completedAt)).update();
    }
}
