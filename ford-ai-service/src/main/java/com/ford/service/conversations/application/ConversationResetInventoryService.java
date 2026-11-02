package com.ford.service.conversations.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.TreeMap;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/** Read-only tenant-scoped reset inventory. This service intentionally exposes no deletion operation. */
@Service
public class ConversationResetInventoryService {
    private final JdbcClient jdbc;

    public ConversationResetInventoryService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly = true)
    public Inventory inventory(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        var counts = new TreeMap<String, Long>();
        counts.put("conversation", count("select count(*) from conversation where tenant_id = :tenant", tenantId));
        counts.put("conversation_message", count("select count(*) from conversation_message m join conversation c on c.id = m.conversation_id where c.tenant_id = :tenant", tenantId));
        counts.put("lead", count("select count(*) from lead where tenant_id = :tenant", tenantId));
        counts.put("lead_score", count("select count(*) from lead_score s join lead l on l.id = s.lead_id where l.tenant_id = :tenant", tenantId));
        counts.put("handoff_ticket", count("select count(*) from handoff_ticket where tenant_id = :tenant", tenantId));
        counts.put("conversation_decision", count("select count(*) from conversation_decision d join conversation c on c.id = d.conversation_id where c.tenant_id = :tenant", tenantId));
        counts.put("conversation_memory", count("select count(*) from conversation_memory where tenant_id = :tenant", tenantId));
        counts.put("conversation_memory_item", count("select count(*) from conversation_memory_item where tenant_id = :tenant", tenantId));
        counts.put("conversation_memory_embedding", count("select count(*) from conversation_memory_embedding where tenant_id = :tenant", tenantId));
        counts.put("lead_journey", count("select count(*) from lead_journey where tenant_id = :tenant", tenantId));
        counts.put("lead_follow_up", count("select count(*) from lead_follow_up where tenant_id = :tenant", tenantId));
        counts.put("conversation_shadow_draft", count("select count(*) from conversation_shadow_draft where tenant_id = :tenant", tenantId));
        counts.put("conversation_turn", count("select count(*) from conversation_turn where tenant_id = :tenant", tenantId));
        counts.put("conversation_review_item", count("select count(*) from conversation_review_item where tenant_id = :tenant", tenantId));
        counts.put("conversation_turn_lease", count("select count(*) from conversation_turn_lease where tenant_id = :tenant", tenantId));
        counts.put("inbound_debounce", count("select count(*) from inbound_debounce where tenant_id = :tenant", tenantId));
        counts.put("graph_thread", jdbc.sql("select count(*) from ford_graph.lg4jthread where thread_name like :prefix")
                .param("prefix", tenantId + ":%").query(Long.class).single());
        counts.put("graph_checkpoint", jdbc.sql("select count(*) from ford_graph.lg4jcheckpoint cp join ford_graph.lg4jthread t on t.thread_id = cp.thread_id where t.thread_name like :prefix")
                .param("prefix", tenantId + ":%").query(Long.class).single());
        counts.put("channel_message", count("select count(*) from channel_message where tenant_id = :tenant", tenantId));
        counts.put("outbox_event", count("select count(*) from outbox_event where tenant_id = :tenant and aggregate_type = 'conversation' and aggregate_id in (select id from conversation where tenant_id = :tenant)", tenantId));
        counts.put("ai_execution", count("select count(*) from ai_execution where tenant_id = :tenant", tenantId));
        counts.put("customer_memory_identity_conversation", count("select count(*) from customer_memory_identity_conversation where tenant_id = :tenant", tenantId));
        counts.put("customer_memory_identity", count("select count(*) from customer_memory_identity where tenant_id = :tenant", tenantId));
        counts.put("customer_memory_identity_fingerprint", count("select count(*) from customer_memory_identity_fingerprint where tenant_id = :tenant", tenantId));
        counts.put("customer_memory_fact", count("select count(*) from customer_memory_fact f join customer_memory_identity i on i.id = f.customer_identity_id where i.tenant_id = :tenant", tenantId));
        counts.put("customer_memory_fact_event", count("select count(*) from customer_memory_fact_event e join customer_memory_identity i on i.id = e.customer_identity_id where i.tenant_id = :tenant", tenantId));
        counts.put("tenant_runtime_configuration", count("select count(*) from tenant_runtime_configuration where tenant_id = :tenant", tenantId));

        var unscopedAuditRows = jdbc.sql("""
                select count(*) from audit_event a
                 where a.tenant_id is null and a.aggregate_id in (
                     select id from conversation where tenant_id = :tenant
                 )
                """).param("tenant", tenantId).query(Long.class).single();
        if (unscopedAuditRows > 0) {
            throw new IllegalStateException("reset inventory is ambiguous: unscoped audit events reference tenant conversations");
        }
        counts.put("audit_event", count("select count(*) from audit_event where tenant_id = :tenant", tenantId));

        var preserved = new TreeMap<String, Long>();
        preserved.put("channel_binding", count("select count(*) from channel_binding where tenant_id = :tenant", tenantId));
        preserved.put("channel_delivery_dedupe_tombstone", count("select count(*) from channel_delivery where tenant_id = :tenant", tenantId));
        return new Inventory(tenantId, counts, preserved, unscopedAuditRows);
    }

    private long count(String sql, UUID tenantId) {
        return jdbc.sql(sql).param("tenant", tenantId).query(Long.class).single();
    }

    public record Inventory(UUID tenantId, Map<String, Long> counts,
                            Map<String, Long> preservedCounts, long ambiguousUnscopedAuditRows) {
        public Inventory {
            counts = Collections.unmodifiableMap(new TreeMap<>(counts));
            preservedCounts = Collections.unmodifiableMap(new TreeMap<>(preservedCounts));
        }
    }
}
