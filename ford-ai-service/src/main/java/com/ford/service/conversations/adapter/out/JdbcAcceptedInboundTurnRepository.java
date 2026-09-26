package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.AcceptedInboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAcceptedInboundTurnRepository implements AcceptedInboundMessageRepository {

    private final JdbcClient jdbc;

    public JdbcAcceptedInboundTurnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AcceptedInboundMessage> find(UUID tenantId, UUID conversationId, UUID receiptId) {
        return jdbc.sql("""
                        select provider_message_id, participant_id, content, received_at
                        from channel_message
                        where id = :receiptId and tenant_id = :tenantId and conversation_id = :conversationId
                        """)
                .param("receiptId", receiptId)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(JdbcAcceptedInboundTurnRepository::map)
                .optional();
    }

    private static AcceptedInboundMessage map(ResultSet rs, int rowNum) throws SQLException {
        return new AcceptedInboundMessage(rs.getString("provider_message_id"),
                rs.getString("participant_id"), rs.getString("content"),
                rs.getTimestamp("received_at").toInstant());
    }
}
