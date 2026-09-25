package com.ford.service.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Duration;
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
                    payload, status, attempts, available_at, idempotency_key)
                values (:id, :tenantId, :aggregateType, :aggregateId, :eventType,
                    cast(:payload as jsonb), :status, :attempts, :availableAt, :idempotencyKey)
                on conflict do nothing
                """)
                .param("id", event.id()).param("tenantId", event.tenantId())
                .param("aggregateType", event.aggregateType()).param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType()).param("payload", event.payload())
                .param("status", event.status().name()).param("attempts", event.attempts())
                .param("availableAt", Timestamp.from(event.availableAt()))
                .param("idempotencyKey", event.idempotencyKey()).update();
    }

    @Override
    @Transactional
    public Optional<OutboxEvent> claimNext(Instant now) {
        return claimNext(now, Duration.ofSeconds(30));
    }

    @Override
    @Transactional
    public Optional<OutboxEvent> claimNext(Instant now, Duration lease) {
        reclaimExpired(now);
        return jdbc.sql("""
                select * from outbox_event
                where status = 'PENDING' and available_at <= :now
                order by available_at, id
                for update skip locked limit 1
                """).param("now", Timestamp.from(now)).query(this::map).optional()
                .map(event -> {
                    var claimed = event.claim(now, lease);
                    jdbc.sql("update outbox_event set status = 'PROCESSING', attempts = :attempts, claimed_at = :claimedAt, lease_until = :leaseUntil where id = :id")
                            .param("id", event.id()).param("attempts", claimed.attempts())
                            .param("claimedAt", Timestamp.from(claimed.claimedAt()))
                            .param("leaseUntil", Timestamp.from(claimed.leaseUntil())).update();
                    return claimed;
                });
    }

    @Override
    public void markDelivered(UUID eventId, String providerMessageId) {
        markDelivered(eventId, providerMessageId, Instant.now());
    }

    @Override
    public void markDelivered(UUID eventId, String providerMessageId, Instant deliveredAt) {
        jdbc.sql("update outbox_event set status = 'DELIVERED', completed_at = :completedAt, provider_delivery_id = :providerDeliveryId, lease_until = null where id = :id")
                .param("id", eventId).param("completedAt", Timestamp.from(deliveredAt))
                .param("providerDeliveryId", providerMessageId).update();
    }

    @Override
    public void markDelivered(UUID eventId, String providerMessageId, Instant deliveredAt, Instant leaseUntil) {
        jdbc.sql("""
                update outbox_event
                   set status = 'DELIVERED', completed_at = :completedAt,
                       provider_delivery_id = :providerDeliveryId, lease_until = null
                 where id = :id and status = 'PROCESSING' and lease_until = :leaseUntil
                """)
                .param("id", eventId).param("completedAt", Timestamp.from(deliveredAt))
                .param("providerDeliveryId", providerMessageId).param("leaseUntil", Timestamp.from(leaseUntil)).update();
    }

    @Override
    public void markFailed(UUID eventId, String error, boolean deadLetter) {
        if (deadLetter) {
            moveToDeadLetter(eventId, "transient", error);
        } else {
            markRetry(eventId, "transient", Instant.now());
        }
    }

    @Override
    public int reclaimExpired(Instant now) {
        return jdbc.sql("""
                update outbox_event
                   set status = 'PENDING', available_at = :now, claimed_at = null, lease_until = null
                 where status = 'PROCESSING' and lease_until is not null and lease_until <= :now
                """)
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    public void markRetry(UUID eventId, String category, Instant nextAttemptAt) {
        jdbc.sql("""
                update outbox_event
                   set status = 'PENDING', available_at = :nextAttemptAt, claimed_at = null,
                       lease_until = null, last_error_category = :category
                 where id = :id
                """)
                .param("id", eventId).param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("category", category).update();
    }

    @Override
    public void markRetry(UUID eventId, String category, Instant nextAttemptAt,
                          String reason, Instant leaseUntil) {
        jdbc.sql("""
                update outbox_event
                   set status = 'PENDING', available_at = :nextAttemptAt, claimed_at = null,
                       lease_until = null, last_error_category = :category, last_error = :reason
                 where id = :id and status = 'PROCESSING' and lease_until = :leaseUntil
                """)
                .param("id", eventId).param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("category", category).param("reason", reason)
                .param("leaseUntil", Timestamp.from(leaseUntil)).update();
    }

    @Override
    public void moveToDeadLetter(UUID eventId, String category, String reason) {
        jdbc.sql("""
                update outbox_event
                   set status = 'DEAD_LETTER', completed_at = :completedAt, claimed_at = null,
                       lease_until = null, last_error_category = :category, last_error = :reason
                 where id = :id
                """)
                .param("id", eventId).param("completedAt", Timestamp.from(Instant.now()))
                .param("category", category).param("reason", reason).update();
    }

    @Override
    public void moveToDeadLetter(UUID eventId, String category, String reason, Instant leaseUntil) {
        jdbc.sql("""
                update outbox_event
                   set status = 'DEAD_LETTER', completed_at = :completedAt, claimed_at = null,
                       lease_until = null, last_error_category = :category, last_error = :reason
                 where id = :id and status = 'PROCESSING' and lease_until = :leaseUntil
                """)
                .param("id", eventId).param("completedAt", Timestamp.from(Instant.now()))
                .param("category", category).param("reason", reason)
                .param("leaseUntil", Timestamp.from(leaseUntil)).update();
    }

    private OutboxEvent map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"), rs.getString("payload"), OutboxEvent.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant(),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                rs.getString("idempotency_key"), rs.getString("provider_delivery_id"),
                rs.getString("last_error_category"));
    }
}
