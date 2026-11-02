package com.ford.service.events.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.events.api.AuditEventPage;
import com.ford.service.events.api.AuditEventView;
import com.ford.service.events.application.AuditEventQueryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcAuditEventRepository implements AuditEventQueryService {
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public AuditEventPage list(UUID tenantId, String cursor, int pageSize, String eventType) {
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page size must be between 1 and 100");
        var sql = new StringBuilder("""
                select id, tenant_id, actor_type, actor_id, event_type, aggregate_type,
                       aggregate_id, metadata, occurred_at
                from audit_event where tenant_id = :tenantId
                """);
        var statement = jdbc.sql(sql.toString()).param("tenantId", tenantId);
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" and event_type = :eventType");
            statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("eventType", eventType);
        }
        var decoded = decode(cursor);
        if (decoded != null) {
            sql.append(" and (occurred_at < :cursorAt or (occurred_at = :cursorAt and id < :cursorId))");
            statement = jdbc.sql(sql.toString()).param("tenantId", tenantId)
                    .param("cursorAt", Timestamp.from(decoded.occurredAt())).param("cursorId", decoded.id());
            if (eventType != null && !eventType.isBlank()) statement = statement.param("eventType", eventType);
        }
        sql.append(" order by occurred_at desc, id desc limit :limit");
        statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("limit", pageSize + 1);
        if (eventType != null && !eventType.isBlank()) statement = statement.param("eventType", eventType);
        if (decoded != null) statement = statement.param("cursorAt", Timestamp.from(decoded.occurredAt())).param("cursorId", decoded.id());
        var rows = statement.query((rs, row) -> new AuditEventView(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("event_type"),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                metadata(rs.getString("metadata")), rs.getTimestamp("occurred_at").toInstant())).list();
        var hasNext = rows.size() > pageSize;
        var items = hasNext ? rows.subList(0, pageSize) : rows;
        var next = hasNext ? encode(items.get(items.size() - 1).occurredAt(), items.get(items.size() - 1).id()) : null;
        return new AuditEventPage(items, next, hasNext);
    }

    private Map<String, Object> metadata(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, METADATA);
        } catch (JsonProcessingException exception) {
            return Map.of("redacted", true);
        }
    }

    private static String encode(Instant occurredAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (occurredAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            var split = decoded.lastIndexOf('|');
            return new Cursor(Instant.parse(decoded.substring(0, split)), UUID.fromString(decoded.substring(split + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record Cursor(Instant occurredAt, UUID id) { }
}
