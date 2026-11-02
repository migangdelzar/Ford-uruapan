package com.ford.service.conversations.adapter.out;

import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.conversations.application.DecisionTraceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcDecisionTraceRepository implements DecisionTraceRepository {
    private final JdbcClient jdbc;

    public JdbcDecisionTraceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void append(UUID tenantId, UUID conversationId, UUID receiptId, List<DecisionTraceEvent> events) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(receiptId, "receiptId is required");
        var safeEvents = List.copyOf(events == null ? List.of() : events);
        for (var sequence = 0; sequence < safeEvents.size(); sequence++) {
            var event = Objects.requireNonNull(safeEvents.get(sequence), "trace event is required");
            jdbc.sql("merge into conversation_decision_event target using (values (cast(:tenant as uuid),"
                            + "cast(:conversation as uuid),cast(:receipt as uuid),cast(:sequence as integer),"
                            + "cast(:stage as varchar(20)),cast(:reason as varchar(64)),cast(:route as varchar(80)),"
                            + "cast(:role as varchar(20)),cast(:tool as varchar(80)),cast(:outcome as varchar(20)),"
                            + "cast(:count as integer),cast(:at as timestamp with time zone))) source "
                            + "(tenant_id,conversation_id,receipt_id,event_sequence,stage,reason_code,route,model_role,"
                            + "tool_name,outcome,evidence_count,occurred_at) on target.tenant_id=source.tenant_id "
                            + "and target.conversation_id=source.conversation_id and target.receipt_id=source.receipt_id "
                            + "and target.event_sequence=source.event_sequence when not matched then insert "
                            + "(tenant_id,conversation_id,receipt_id,event_sequence,stage,reason_code,route,model_role,"
                            + "tool_name,outcome,evidence_count,occurred_at) values (source.tenant_id,source.conversation_id,"
                            + "source.receipt_id,source.event_sequence,source.stage,source.reason_code,source.route,"
                            + "source.model_role,source.tool_name,source.outcome,source.evidence_count,source.occurred_at)")
                    .param("tenant", tenantId).param("conversation", conversationId).param("receipt", receiptId)
                    .param("sequence", sequence).param("stage", event.stage()).param("reason", event.reasonCode())
                    .param("route", emptyToNull(event.route())).param("role", emptyToNull(event.modelRole()))
                    .param("tool", emptyToNull(event.toolName())).param("outcome", event.outcome())
                    .param("count", event.evidenceCount()).param("at", Timestamp.from(event.occurredAt())).update();
        }
    }

    @Override
    public List<DecisionTraceEvent> list(UUID tenantId, UUID conversationId, UUID receiptId) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(receiptId, "receiptId is required");
        return jdbc.sql("select stage,reason_code,route,model_role,tool_name,outcome,evidence_count,occurred_at "
                        + "from conversation_decision_event where tenant_id=:tenant and conversation_id=:conversation "
                        + "and receipt_id=:receipt order by event_sequence limit 100")
                .param("tenant", tenantId).param("conversation", conversationId).param("receipt", receiptId)
                .query((rs, row) -> new DecisionTraceEvent(rs.getString("stage"), rs.getString("reason_code"),
                        rs.getString("route"), rs.getString("model_role"), rs.getString("tool_name"),
                        rs.getString("outcome"), (Integer) rs.getObject("evidence_count"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
