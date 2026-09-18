package com.ford.service.handoff.adapter.out;

import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcHandoffRepository implements HandoffRepository {

    private final JdbcClient jdbc;

    public JdbcHandoffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HandoffTicket save(HandoffTicket ticket) {
        jdbc.sql("""
                insert into handoff_ticket
                    (id, tenant_id, conversation_id, lead_id, status, assigned_operator_id,
                     reason, version, created_at, claimed_at, closed_at)
                values (:id, :tenantId, :conversationId, :leadId, :status, :operatorId,
                        :reason, :version, :createdAt, :claimedAt, :closedAt)
                on conflict (id) do update set
                    status = excluded.status,
                    assigned_operator_id = excluded.assigned_operator_id,
                    version = excluded.version,
                    claimed_at = excluded.claimed_at,
                    closed_at = excluded.closed_at
                """)
                .param("id", ticket.id())
                .param("tenantId", ticket.tenantId())
                .param("conversationId", ticket.conversationId())
                .param("leadId", ticket.leadId())
                .param("status", ticket.status().name())
                .param("operatorId", ticket.assignedOperatorId())
                .param("reason", ticket.reason())
                .param("version", ticket.version())
                .param("createdAt", ticket.createdAt())
                .param("claimedAt", ticket.claimedAt())
                .param("closedAt", ticket.closedAt())
                .update();
        return ticket;
    }

    @Override
    public HandoffTicket findRequired(UUID id) {
        return jdbc.sql("select * from handoff_ticket where id = :id")
                .param("id", id)
                .query(this::map)
                .single();
    }

    @Override
    public HandoffTicket findByConversation(UUID conversationId) {
        return jdbc.sql("select * from handoff_ticket where conversation_id = :conversationId order by created_at desc limit 1")
                .param("conversationId", conversationId)
                .query(this::map)
                .single();
    }

    @Override
    public List<HandoffTicket> list(UUID tenantId) {
        return jdbc.sql("select * from handoff_ticket where tenant_id = :tenantId order by created_at desc")
                .param("tenantId", tenantId)
                .query(this::map)
                .list();
    }

    private HandoffTicket map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new HandoffTicket(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("lead_id", UUID.class),
                HandoffStatus.valueOf(rs.getString("status")),
                rs.getObject("assigned_operator_id", UUID.class),
                rs.getString("reason"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "claimed_at"),
                instant(rs, "closed_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
