package com.ford.service.inventoryapproval.adapter.out;

import com.ford.service.inventoryapproval.application.port.out.InventoryApprovalRepository;
import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcInventoryApprovalRepository implements InventoryApprovalRepository {
    private static final RowMapper<InventoryApproval> ROW_MAPPER = (rs, row) -> new InventoryApproval(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("conversation_id", UUID.class), rs.getString("model"),
            (Integer) rs.getObject("model_year"), rs.getString("trim"),
            InventoryApprovalStatus.valueOf(rs.getString("status")), enumValue(InventoryOutcome.class, rs.getString("outcome")),
            (Integer) rs.getObject("verified_count"), instant(rs.getTimestamp("verified_at")),
            instant(rs.getTimestamp("verification_expires_at")), rs.getString("draft_text"),
            rs.getString("approved_text"), rs.getObject("verified_by", UUID.class),
            rs.getObject("approved_by", UUID.class), rs.getString("idempotency_key"),
            rs.getLong("version"), rs.getObject("rejected_by", UUID.class),
            instant(rs.getTimestamp("created_at")));

    private final JdbcClient jdbc;

    public JdbcInventoryApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InventoryApproval savePending(InventoryApproval approval) {
        if (approval.status() != InventoryApprovalStatus.PENDING || approval.version() != 0) {
            throw new IllegalArgumentException("only a new pending approval can be inserted");
        }
        var existing = findByIdempotencyKey(approval.tenantId(), approval.idempotencyKey());
        if (existing.isPresent()) return sameRequest(existing.get(), approval);
        try {
            jdbc.sql("""
                    insert into inventory_approval
                        (id, tenant_id, conversation_id, model, model_year, trim, status,
                         idempotency_key, version, created_at, updated_at)
                    values (:id, :tenantId, :conversationId, :model, :year, :trim, 'PENDING',
                            :idempotencyKey, 0, :createdAt, :createdAt)
                    """)
                    .param("id", approval.id()).param("tenantId", approval.tenantId())
                    .param("conversationId", approval.conversationId()).param("model", approval.model())
                    .param("year", approval.year()).param("trim", approval.trim())
                    .param("idempotencyKey", approval.idempotencyKey())
                    .param("createdAt", Timestamp.from(approval.createdAt())).update();
            return approval;
        } catch (DuplicateKeyException duplicate) {
            return findByIdempotencyKey(approval.tenantId(), approval.idempotencyKey())
                    .map(found -> sameRequest(found, approval))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<InventoryApproval> find(UUID tenantId, UUID approvalId) {
        if (tenantId == null || approvalId == null) return Optional.empty();
        return jdbc.sql("select * from inventory_approval where tenant_id = :tenantId and id = :id")
                .param("tenantId", tenantId).param("id", approvalId)
                .query(ROW_MAPPER).optional();
    }

    @Override
    public Optional<InventoryApproval> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null) return Optional.empty();
        return jdbc.sql("select * from inventory_approval where tenant_id = :tenantId and idempotency_key = :key")
                .param("tenantId", tenantId).param("key", idempotencyKey)
                .query(ROW_MAPPER).optional();
    }

    @Override
    public List<InventoryApproval> list(UUID tenantId, InventoryApprovalStatus status, int limit) {
        if (tenantId == null || limit < 1) return List.of();
        var boundedLimit = Math.min(limit, 100);
        return jdbc.sql("""
                        select * from inventory_approval
                        where tenant_id = :tenantId and (:status is null or status = :status)
                        order by created_at desc, id
                        limit :limit
                        """)
                .param("tenantId", tenantId).param("status", status == null ? null : status.name())
                .param("limit", boundedLimit).query(ROW_MAPPER).list();
    }

    @Override
    public Optional<InventoryApproval> transition(UUID tenantId, UUID approvalId, long expectedVersion,
                                                  InventoryApprovalStatus expectedStatus,
                                                  InventoryApproval replacement) {
        if (replacement == null || tenantId == null || approvalId == null || expectedStatus == null
                || !tenantId.equals(replacement.tenantId()) || !approvalId.equals(replacement.id())
                || replacement.version() != expectedVersion + 1 || replacement.status() == expectedStatus) {
            throw new IllegalArgumentException("approval replacement does not match the expected transition");
        }
        var updated = jdbc.sql("""
                        update inventory_approval
                        set status = :status, outcome = :outcome, verified_count = :verifiedCount,
                            verified_at = :verifiedAt, verification_expires_at = :expiresAt,
                            draft_text = :draftText, approved_text = :approvedText,
                            verified_by = :verifiedBy, approved_by = :approvedBy, rejected_by = :rejectedBy,
                            idempotency_key = :idempotencyKey, version = :nextVersion, updated_at = :updatedAt
                        where tenant_id = :tenantId and id = :id
                          and version = :expectedVersion and status = :expectedStatus
                        """)
                .param("status", replacement.status().name())
                .param("outcome", replacement.outcome() == null ? null : replacement.outcome().name())
                .param("verifiedCount", replacement.verifiedCount())
                .param("verifiedAt", timestamp(replacement.verifiedAt()))
                .param("expiresAt", timestamp(replacement.verificationExpiresAt()))
                .param("draftText", replacement.draftText()).param("approvedText", replacement.approvedText())
                .param("verifiedBy", replacement.verifiedBy()).param("approvedBy", replacement.approvedBy())
                .param("rejectedBy", replacement.rejectedBy()).param("idempotencyKey", replacement.idempotencyKey())
                .param("nextVersion", replacement.version()).param("updatedAt", Timestamp.from(Instant.now()))
                .param("tenantId", tenantId).param("id", approvalId).param("expectedVersion", expectedVersion)
                .param("expectedStatus", expectedStatus.name()).update();
        return updated == 1 ? find(tenantId, approvalId) : Optional.empty();
    }

    @Override
    public void recordAudit(UUID tenantId, UUID approvalId, UUID actorId, String action,
                            Instant occurredAt, long approvalVersion) {
        if (tenantId == null || approvalId == null || actorId == null || action == null || action.isBlank()
                || action.length() > 32 || occurredAt == null || approvalVersion < 0) {
            throw new IllegalArgumentException("valid approval audit metadata is required");
        }
        jdbc.sql("""
                        insert into inventory_approval_audit
                            (id, tenant_id, approval_id, actor_id, action, occurred_at, approval_version)
                        values (:id, :tenantId, :approvalId, :actorId, :action, :occurredAt, :version)
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("approvalId", approvalId)
                .param("actorId", actorId).param("action", action)
                .param("occurredAt", Timestamp.from(occurredAt)).param("version", approvalVersion).update();
    }

    private static InventoryApproval sameRequest(InventoryApproval existing, InventoryApproval request) {
        if (!existing.conversationId().equals(request.conversationId())
                || !existing.model().equals(request.model()) || !java.util.Objects.equals(existing.year(), request.year())
                || !java.util.Objects.equals(existing.trim(), request.trim())) {
            throw new IllegalStateException("inventory approval idempotency key conflicts with request identity");
        }
        return existing;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> enumType, String value) {
        return value == null ? null : Enum.valueOf(enumType, value);
    }
}
