package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.AcceptedInboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAcceptedInboundTurnRepository implements AcceptedInboundMessageRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAcceptedInboundTurnRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AcceptedInboundMessage> find(UUID tenantId, UUID conversationId, UUID receiptId) {
        return jdbc.sql("""
                        select provider_message_id, participant_id, content, received_at,
                               message_kind, attachment_metadata::text as attachment_metadata
                        from channel_message
                        where id = :receiptId and tenant_id = :tenantId and conversation_id = :conversationId
                        """)
                .param("receiptId", receiptId)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> map(rs, objectMapper))
                .optional();
    }

    @Override
    @Transactional
    public void persistResolvedContent(UUID tenantId, UUID conversationId, UUID receiptId, String text) {
        jdbc.sql("""
                update channel_message set content = :content
                where id = :receiptId and tenant_id = :tenantId and conversation_id = :conversationId
                """)
                .param("content", text)
                .param("receiptId", receiptId)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                update conversation_message set content = :content
                where provider_message_id = (select provider_message_id from channel_message
                    where id = :receiptId and tenant_id = :tenantId and conversation_id = :conversationId)
                  and conversation_id = :conversationId and direction = 'INBOUND'
                """)
                .param("content", text)
                .param("receiptId", receiptId)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .update();
    }

    private static AcceptedInboundMessage map(ResultSet rs, ObjectMapper objectMapper) throws SQLException {
        try {
            var attachments = objectMapper.readValue(rs.getString("attachment_metadata"),
                    new TypeReference<java.util.List<InboundAttachment>>() { });
            return new AcceptedInboundMessage(rs.getString("provider_message_id"),
                    rs.getString("participant_id"), rs.getString("content"),
                    rs.getTimestamp("received_at").toInstant(),
                    InboundMessageKind.valueOf(rs.getString("message_kind")), attachments);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new SQLException("Stored inbound message metadata is invalid", exception);
        }
    }
}
