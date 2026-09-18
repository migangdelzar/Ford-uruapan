package com.ford.service.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOutboxStore implements OutboxStore {
    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxEvent event) {
        jdbc.sql("""
                insert into outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type,
                    payload, status, attempts, available_at)
                values (:id, :tenantId, :aggregateType, :aggregateId, :eventType,
                    cast(:payload as jsonb), :status, :attempts, :availableAt)
                on conflict (id) do nothing
                """)
                .param("id", event.id()).param("tenantId", event.tenantId())
                .param("aggregateType", event.aggregateType()).param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType()).param("payload", event.payload())
                .param("status", event.status().name()).param("attempts", event.attempts())
                .param("availableAt", Timestamp.from(event.availableAt())).update();
    }

    @Override
    public Optional<OutboxEvent> claimNext(Instant now) {
        return jdbc.sql("""
                select * from outbox_event
                where status = 'PENDING' and available_at <= :now
                order by available_at, id
                for update skip locked limit 1
                """).param("now", Timestamp.from(now)).query(this::map).optional()
                .map(event -> {
                    var claimed = event.claim();
                    jdbc.sql("update outbox_event set status = 'PROCESSING', attempts = :attempts, claimed_at = :claimedAt where id = :id")
                            .param("id", event.id()).param("attempts", claimed.attempts())
                            .param("claimedAt", Timestamp.from(claimed.claimedAt())).update();
                    return claimed;
                });
    }

    @Override
    public void markDelivered(UUID eventId, String providerMessageId) {
        jdbc.sql("update outbox_event set status = 'DELIVERED', completed_at = :completedAt where id = :id")
                .param("id", eventId).param("completedAt", Timestamp.from(Instant.now())).update();
    }

    @Override
    public void markFailed(UUID eventId, String error, boolean deadLetter) {
        jdbc.sql("update outbox_event set status = :status, available_at = :availableAt, last_error = :error where id = :id")
                .param("id", eventId).param("status", deadLetter ? "DEAD_LETTER" : "PENDING")
                .param("availableAt", Timestamp.from(Instant.now())).param("error", error).update();
    }

    private OutboxEvent map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"), rs.getString("payload"), OutboxEvent.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant());
    }
}
