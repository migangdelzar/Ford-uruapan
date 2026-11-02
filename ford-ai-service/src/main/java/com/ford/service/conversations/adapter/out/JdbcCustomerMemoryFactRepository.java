package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcCustomerMemoryFactRepository implements CustomerMemoryFactRepository {

    private final JdbcClient jdbc;

    public JdbcCustomerMemoryFactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean hasProcessedSource(UUID identityId, UUID sourceReceiptId) {
        return jdbc.sql("select exists(select 1 from customer_memory_fact_event where customer_identity_id=:identityId and source_receipt_id=:receiptId and source_type='CUSTOMER')")
                .param("identityId", identityId).param("receiptId", sourceReceiptId).query(Boolean.class).single();
    }

    @Override
    @Transactional
    public FactUpdateResult upsert(CustomerShoppingFact fact) {
        var identityLock = jdbc.sql("select id from customer_memory_identity where id=:id for update")
                .param("id", fact.identityId()).query(UUID.class).optional().orElse(null);
        if (identityLock == null) throw new IllegalArgumentException("customer identity does not exist");
        var forgetBefore = jdbc.sql("select facts_forget_before from customer_memory_identity where id=:id")
                .param("id", fact.identityId()).query((rs, row) -> {
                    var value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }).optional().orElse(null);
        if (forgetBefore != null && !fact.sourceReceivedAt().isAfter(forgetBefore)) {
            return new FactUpdateResult(false, -1);
        }
        var previous = jdbc.sql("""
                select state, source_received_at, source_receipt_id, version
                from customer_memory_fact where customer_identity_id=:identityId
                  and fact_type=:type and subject_key=:subjectKey for update
                """)
                .param("identityId", fact.identityId()).param("type", fact.type().name())
                .param("subjectKey", fact.subjectKey())
                .query((rs, row) -> new PreviousFact(rs.getString("state"),
                        rs.getTimestamp("source_received_at").toInstant(),
                        rs.getObject("source_receipt_id", UUID.class), rs.getLong("version")))
                .optional().orElse(null);
        if (previous != null && compareSource(fact, previous) <= 0) {
            return new FactUpdateResult(false, previous.version());
        }
        if (jdbc.sql("select exists(select 1 from customer_memory_fact_event where customer_identity_id=:identityId and source_receipt_id=:receiptId and fact_type=:type and subject_key=:subjectKey and source_type='CUSTOMER')")
                .param("identityId", fact.identityId()).param("receiptId", fact.sourceReceiptId())
                .param("type", fact.type().name()).param("subjectKey", fact.subjectKey())
                .query(Boolean.class).single()) {
            return new FactUpdateResult(false, previous == null ? -1 : previous.version());
        }
        var nextVersion = previous == null ? 0 : previous.version() + 1;
        jdbc.sql("""
                insert into customer_memory_fact(customer_identity_id, fact_type, subject_key, fact_value,
                    state, source_conversation_id, source_receipt_id, source_received_at, source_version,
                    stated_at, updated_at, expires_at, version)
                values (:identityId, :type, :subjectKey, :value, :state, :conversationId, :receiptId,
                    :sourceReceivedAt, :sourceVersion, :statedAt, :updatedAt, :expiresAt, :version)
                on conflict (customer_identity_id, fact_type, subject_key) do update set
                    fact_value=excluded.fact_value, state=excluded.state,
                    source_conversation_id=excluded.source_conversation_id,
                    source_receipt_id=excluded.source_receipt_id,
                    source_received_at=excluded.source_received_at,
                    source_version=excluded.source_version, stated_at=excluded.stated_at,
                    updated_at=excluded.updated_at, expires_at=excluded.expires_at, version=excluded.version
                """)
                .param("identityId", fact.identityId()).param("type", fact.type().name())
                .param("subjectKey", fact.subjectKey()).param("value", fact.value())
                .param("state", fact.state().name()).param("conversationId", fact.sourceConversationId())
                .param("receiptId", fact.sourceReceiptId()).param("sourceReceivedAt", Timestamp.from(fact.sourceReceivedAt()))
                .param("sourceVersion", fact.sourceVersion()).param("statedAt", Timestamp.from(fact.statedAt()))
                .param("updatedAt", Timestamp.from(fact.updatedAt()))
                .param("expiresAt", fact.expiresAt() == null ? null : Timestamp.from(fact.expiresAt()))
                .param("version", nextVersion).update();
        var priorState = previous == null ? null : previous.state();
        jdbc.sql("""
                insert into customer_memory_fact_event(id, customer_identity_id, fact_type, subject_key,
                    prior_state, new_state, source_receipt_id, source_type, occurred_at)
                values (:id, :identityId, :type, :subjectKey, :priorState, :newState, :receiptId, 'CUSTOMER', :occurredAt)
                on conflict (customer_identity_id, source_receipt_id, fact_type, subject_key, source_type) do nothing
                """)
                .param("id", UUID.randomUUID()).param("identityId", fact.identityId())
                .param("type", fact.type().name()).param("subjectKey", fact.subjectKey())
                .param("priorState", priorState).param("newState", fact.state().name())
                .param("receiptId", fact.sourceReceiptId()).param("occurredAt", Timestamp.from(fact.updatedAt()))
                .update();
        return new FactUpdateResult(true, nextVersion);
    }

    @Override
    public List<CustomerShoppingFact> findCurrent(UUID identityId, Instant now, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        return jdbc.sql("""
                select * from customer_memory_fact where customer_identity_id=:identityId
                  and (state='DECLINED' or expires_at > :now)
                order by updated_at desc limit :limit
                """)
                .param("identityId", identityId).param("now", Timestamp.from(now)).param("limit", limit)
                .query((rs, row) -> new CustomerShoppingFact(identityId,
                        CustomerShoppingFactType.valueOf(rs.getString("fact_type")), rs.getString("subject_key"),
                        rs.getString("fact_value"), CustomerShoppingFactState.valueOf(rs.getString("state")),
                        rs.getObject("source_conversation_id", UUID.class),
                        rs.getObject("source_receipt_id", UUID.class), rs.getTimestamp("source_received_at").toInstant(),
                        rs.getInt("source_version"), rs.getTimestamp("stated_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getLong("version"))).list();
    }

    @Override
    @Transactional
    public ForgetResult forget(UUID identityId, Instant forgottenAt, UUID auditId, String actorId) {
        jdbc.sql("select id from customer_memory_identity where id=:id for update")
                .param("id", identityId).query(UUID.class).single();
        var removed = jdbc.sql("delete from customer_memory_fact where customer_identity_id=:id")
                .param("id", identityId).update();
        var current = jdbc.sql("select facts_forget_before from customer_memory_identity where id=:id")
                .param("id", identityId).query((rs, row) -> {
                    var value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }).optional().orElse(null);
        var effectiveForgottenAt = current != null && current.isAfter(forgottenAt) ? current : forgottenAt;
        jdbc.sql("update customer_memory_identity set facts_forget_before=:forgottenAt where id=:id and (facts_forget_before is null or facts_forget_before < :forgottenAt)")
                .param("forgottenAt", Timestamp.from(effectiveForgottenAt)).param("id", identityId).update();
        jdbc.sql("""
                insert into customer_memory_fact_event(id, customer_identity_id, fact_type, subject_key,
                    prior_state, new_state, source_receipt_id, source_type, occurred_at, actor_id)
                values (:id, :identityId, 'FORGET_ALL', '*', null, 'FORGOTTEN', :id, 'OPERATOR', :at, :actorId)
                on conflict do nothing
                """)
                .param("id", auditId).param("identityId", identityId).param("at", Timestamp.from(effectiveForgottenAt))
                .param("actorId", actorId).update();
        return new ForgetResult(removed, effectiveForgottenAt);
    }

    @Override
    @Transactional
    public void reopen(UUID identityId, CustomerShoppingFactType type, String subjectKey,
                       UUID sourceReceiptId, Instant reopenedAt) {
        var deleted = jdbc.sql("delete from customer_memory_fact where customer_identity_id=:identityId and fact_type=:type and subject_key=:subjectKey and state='DECLINED'")
                .param("identityId", identityId).param("type", type.name()).param("subjectKey", subjectKey)
                .update();
        if (deleted == 0) return;
        jdbc.sql("""
                insert into customer_memory_fact_event(id, customer_identity_id, fact_type, subject_key,
                    prior_state, new_state, source_receipt_id, source_type, occurred_at)
                values (:id, :identityId, :type, :subjectKey, 'DECLINED', 'REOPENED', :receiptId, 'CUSTOMER', :at)
                on conflict (customer_identity_id, source_receipt_id, fact_type, subject_key, source_type) do nothing
                """)
                .param("id", UUID.randomUUID()).param("identityId", identityId).param("type", type.name())
                .param("subjectKey", subjectKey).param("receiptId", sourceReceiptId)
                .param("at", Timestamp.from(reopenedAt)).update();
    }

    private static int compareSource(CustomerShoppingFact fact, PreviousFact current) {
        var time = fact.sourceReceivedAt().compareTo(current.sourceReceivedAt());
        return time == 0 ? fact.sourceReceiptId().compareTo(current.receiptId()) : time;
    }

    private record PreviousFact(String state, Instant sourceReceivedAt, UUID receiptId, long version) { }
}
