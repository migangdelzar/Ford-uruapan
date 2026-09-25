package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationFilter;
import com.ford.service.conversations.api.ConversationMessagePage;
import com.ford.service.conversations.api.ConversationMessageView;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.ConversationView;
import com.ford.service.conversations.api.PageInfoView;
import com.ford.service.conversations.api.ParticipantView;
import com.ford.service.conversations.application.port.in.ConversationQuery;
import com.ford.service.conversations.application.port.out.OperatorConversationRepository;
import com.ford.service.conversations.domain.ConversationMessage.Direction;
import com.ford.service.conversations.domain.ConversationState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOperatorConversationRepository implements OperatorConversationRepository, ConversationQuery {

    private final JdbcClient jdbc;

    public JdbcOperatorConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationPage search(ConversationFilter filter) {
        var sql = new StringBuilder("""
                select c.id, c.tenant_id, c.channel, c.state, c.version,
                       c.participant_id, l.id as lead_id, c.updated_at,
                       m.id as latest_id, m.provider_message_id as latest_provider_message_id,
                       m.direction as latest_direction, m.content as latest_content,
                       m.received_at as latest_received_at
                from conversation c
                left join lateral (
                    select id, provider_message_id, direction, content, received_at
                    from conversation_message
                    where conversation_id = c.id
                    order by received_at desc, id desc
                    limit 1
                ) m on true
                left join lateral (
                    select id
                    from lead
                    where conversation_id = c.id and tenant_id = c.tenant_id
                    order by updated_at desc, id desc
                    limit 1
                ) l on true
                where c.tenant_id = :tenantId
                """);
        var statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId());
        if (filter.state() != null) {
            sql.append(" and c.state = :state");
            statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId())
                    .param("state", filter.state().name());
        }
        if (filter.channel() != null) {
            sql.append(" and c.channel = :channel");
            statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId())
                    .param("channel", filter.channel().name());
            if (filter.state() != null) {
                statement = statement.param("state", filter.state().name());
            }
        }
        if (filter.assignedOperatorId() != null) {
            // Assignment is represented by the active handoff ticket until the
            // conversation projection gains a dedicated assignment column.
            sql.append(" and exists (select 1 from handoff_ticket h where h.conversation_id = c.id "
                    + "and h.tenant_id = c.tenant_id and h.assigned_operator_id = :assignedOperatorId "
                    + "and h.status = 'HUMAN_ACTIVE')");
            statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId())
                    .param("assignedOperatorId", filter.assignedOperatorId());
            if (filter.state() != null) statement = statement.param("state", filter.state().name());
            if (filter.channel() != null) statement = statement.param("channel", filter.channel().name());
        }
        if (filter.search() != null) {
            sql.append(" and lower(coalesce(c.participant_id, '') || ' ' || coalesce(m.content, '')) like :search");
            statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId())
                    .param("search", "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%");
            if (filter.state() != null) statement = statement.param("state", filter.state().name());
            if (filter.channel() != null) statement = statement.param("channel", filter.channel().name());
            if (filter.assignedOperatorId() != null) statement = statement.param("assignedOperatorId", filter.assignedOperatorId());
        }
        var cursor = decode(filter.cursor());
        if (cursor != null) {
            sql.append(" and (c.updated_at < :cursorAt or (c.updated_at = :cursorAt and c.id < :cursorId))");
            statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId())
                    .param("cursorAt", Timestamp.from(cursor.updatedAt()))
                    .param("cursorId", cursor.id());
            if (filter.state() != null) statement = statement.param("state", filter.state().name());
            if (filter.channel() != null) statement = statement.param("channel", filter.channel().name());
            if (filter.assignedOperatorId() != null) statement = statement.param("assignedOperatorId", filter.assignedOperatorId());
            if (filter.search() != null) statement = statement.param("search", "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        sql.append(" order by c.updated_at desc, c.id desc limit :limit");
        statement = jdbc.sql(sql.toString()).param("tenantId", filter.tenantId()).param("limit", filter.pageSize() + 1);
        if (filter.state() != null) statement = statement.param("state", filter.state().name());
        if (filter.channel() != null) statement = statement.param("channel", filter.channel().name());
        if (filter.assignedOperatorId() != null) statement = statement.param("assignedOperatorId", filter.assignedOperatorId());
        if (filter.search() != null) statement = statement.param("search", "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%");
        if (cursor != null) {
            statement = statement.param("cursorAt", Timestamp.from(cursor.updatedAt())).param("cursorId", cursor.id());
        }
        var rows = statement.query(this::mapConversation).list();
        var hasNext = rows.size() > filter.pageSize();
        var items = hasNext ? rows.subList(0, filter.pageSize()) : rows;
        var nextCursor = hasNext ? encode(items.get(items.size() - 1).updatedAt(), items.get(items.size() - 1).id()) : null;
        return new ConversationPage(items, new PageInfoView(items.size(), nextCursor, hasNext));
    }

    @Override
    public Optional<ConversationView> find(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select c.id, c.tenant_id, c.channel, c.state, c.version,
                       c.participant_id, l.id as lead_id, c.updated_at,
                       m.id as latest_id, m.provider_message_id as latest_provider_message_id,
                       m.direction as latest_direction, m.content as latest_content,
                       m.received_at as latest_received_at
                from conversation c
                left join lateral (
                    select id from lead
                    where conversation_id = c.id and tenant_id = c.tenant_id
                    order by updated_at desc, id desc limit 1
                ) l on true
                left join lateral (
                    select id, provider_message_id, direction, content, received_at
                    from conversation_message where conversation_id = c.id
                    order by received_at desc, id desc limit 1
                ) m on true
                where c.id = :conversationId and c.tenant_id = :tenantId
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .query(this::mapConversation)
                .optional();
    }

    @Override
    public ConversationMessagePage messages(UUID tenantId, UUID conversationId, String cursor, int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page size must be between 1 and 100");
        }
        var sql = new StringBuilder("""
                select m.id, m.conversation_id, m.provider_message_id, m.direction,
                       m.content, m.received_at, c.participant_id
                from conversation_message m
                join conversation c on c.id = m.conversation_id and c.tenant_id = :tenantId
                where m.conversation_id = :conversationId
                """);
        var statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("conversationId", conversationId);
        var decoded = decode(cursor);
        if (decoded != null) {
            sql.append(" and (m.received_at < :cursorAt or (m.received_at = :cursorAt and m.id < :cursorId))");
            statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("conversationId", conversationId)
                    .param("cursorAt", Timestamp.from(decoded.updatedAt())).param("cursorId", decoded.id());
        }
        sql.append(" order by m.received_at desc, m.id desc limit :limit");
        statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("conversationId", conversationId)
                .param("limit", pageSize + 1);
        if (decoded != null) {
            statement = statement.param("cursorAt", Timestamp.from(decoded.updatedAt())).param("cursorId", decoded.id());
        }
        var rows = statement.query((rs, rowNum) -> new ConversationMessageView(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getString("provider_message_id"),
                Direction.valueOf(rs.getString("direction")),
                rs.getString("content"),
                null,
                new ParticipantView(rs.getString("participant_id"), null, null),
                rs.getTimestamp("received_at").toInstant(),
                Direction.OUTBOUND.name().equals(rs.getString("direction")) ? rs.getTimestamp("received_at").toInstant() : null,
                Map.of())).list();
        var hasNext = rows.size() > pageSize;
        var items = hasNext ? rows.subList(0, pageSize) : rows;
        var nextCursor = hasNext ? encode(items.get(items.size() - 1).receivedAt(), items.get(items.size() - 1).id()) : null;
        return new ConversationMessagePage(items, new PageInfoView(items.size(), nextCursor, hasNext));
    }

    private ConversationView mapConversation(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ConversationMessageView latest = null;
        var latestId = rs.getObject("latest_id", UUID.class);
        if (latestId != null) {
            latest = new ConversationMessageView(
                    latestId,
                    rs.getObject("id", UUID.class),
                    rs.getString("latest_provider_message_id"),
                    Direction.valueOf(rs.getString("latest_direction")),
                    rs.getString("latest_content"),
                    null,
                    new ParticipantView(rs.getString("participant_id"), null, null),
                    rs.getTimestamp("latest_received_at").toInstant(),
                    null,
                    Map.of());
        }
        return new ConversationView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                Channel.valueOf(rs.getString("channel")),
                ConversationState.valueOf(rs.getString("state")),
                rs.getLong("version"),
                new ParticipantView(rs.getString("participant_id"), null, null),
                null,
                rs.getObject("lead_id", UUID.class),
                latest,
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String encode(Instant updatedAt, UUID id) {
        var value = updatedAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            var value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separator = value.lastIndexOf('|');
            if (separator <= 0) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(value.substring(0, separator)), UUID.fromString(value.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record Cursor(Instant updatedAt, UUID id) {
    }
}
