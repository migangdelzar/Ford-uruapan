package com.ford.service.leads.adapter.out;

import com.ford.service.leads.api.LeadPage;
import com.ford.service.leads.api.LeadView;
import com.ford.service.leads.application.LeadQueryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLeadQueryRepository implements LeadQueryService {
    private final JdbcClient jdbc;

    public JdbcLeadQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LeadPage list(UUID tenantId, String cursor, int pageSize, String temperature) {
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page size must be between 1 and 100");
        var sql = new StringBuilder("""
                select l.id, l.tenant_id, l.conversation_id, l.name, l.phone, l.email,
                       l.preferred_vehicle, l.budget_range, l.updated_at,
                       latest.band, latest.score
                from lead l
                left join lateral (select band, score from lead_score where lead_id = l.id
                                   order by created_at desc limit 1) latest on true
                where l.tenant_id = :tenantId
                """);
        var statement = jdbc.sql(sql.toString()).param("tenantId", tenantId);
        if (temperature != null && !temperature.isBlank()) {
            sql.append(" and upper(latest.band) = upper(:temperature)");
            statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("temperature", temperature);
        }
        var decoded = decode(cursor);
        if (decoded != null) {
            sql.append(" and (l.updated_at < :cursorAt or (l.updated_at = :cursorAt and l.id < :cursorId))");
            statement = jdbc.sql(sql.toString()).param("tenantId", tenantId)
                    .param("cursorAt", Timestamp.from(decoded.updatedAt())).param("cursorId", decoded.id());
            if (temperature != null && !temperature.isBlank()) statement = statement.param("temperature", temperature);
        }
        sql.append(" order by l.updated_at desc, l.id desc limit :limit");
        statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("limit", pageSize + 1);
        if (temperature != null && !temperature.isBlank()) statement = statement.param("temperature", temperature);
        if (decoded != null) statement = statement.param("cursorAt", Timestamp.from(decoded.updatedAt())).param("cursorId", decoded.id());
        var rows = statement.query((rs, row) -> map(rs)).list();
        var hasNext = rows.size() > pageSize;
        var items = hasNext ? rows.subList(0, pageSize) : rows;
        var next = hasNext ? encode(items.get(items.size() - 1).updatedAt(), items.get(items.size() - 1).id()) : null;
        return new LeadPage(items, next, hasNext);
    }

    @Override
    public Optional<LeadView> find(UUID tenantId, UUID leadId) {
        return jdbc.sql("""
                select l.id, l.tenant_id, l.conversation_id, l.name, l.phone, l.email,
                       l.preferred_vehicle, l.budget_range, l.updated_at, latest.band, latest.score
                from lead l
                left join lateral (select band, score from lead_score where lead_id = l.id
                                   order by created_at desc limit 1) latest on true
                where l.id = :leadId and l.tenant_id = :tenantId
                """)
                .param("leadId", leadId).param("tenantId", tenantId)
                .query((rs, row) -> map(rs)).optional();
    }

    private static LeadView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LeadView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("name"), rs.getString("phone"),
                rs.getString("email"), rs.getString("preferred_vehicle"), rs.getString("budget_range"),
                rs.getString("band"), rs.getObject("score", Integer.class), rs.getTimestamp("updated_at").toInstant());
    }

    private static String encode(Instant updatedAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((updatedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
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

    private record Cursor(Instant updatedAt, UUID id) { }
}
