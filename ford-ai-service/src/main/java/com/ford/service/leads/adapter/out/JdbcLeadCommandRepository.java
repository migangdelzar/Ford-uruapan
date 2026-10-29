package com.ford.service.leads.adapter.out;

import com.ford.service.leads.api.LeadUpdateRequest;
import com.ford.service.leads.api.LeadView;
import com.ford.service.leads.application.LeadCommandService.LeadCommandRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLeadCommandRepository implements LeadCommandRepository {
    private final JdbcClient jdbc;

    public JdbcLeadCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeadView> find(UUID tenantId, UUID leadId) {
        return jdbc.sql("""
                select l.id, l.tenant_id, l.conversation_id, l.name, l.phone, l.email,
                       l.preferred_vehicle, l.budget_range, l.updated_at, l.version,
                       latest.band, latest.score
                  from lead l
                  left join lateral (select band, score from lead_score where lead_id = l.id
                                     order by created_at desc limit 1) latest on true
                 where l.id = :leadId and l.tenant_id = :tenantId
                """)
                .param("leadId", leadId).param("tenantId", tenantId)
                .query((rs, row) -> new LeadView(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getString("name"), rs.getString("phone"), rs.getString("email"),
                        rs.getString("preferred_vehicle"), rs.getString("budget_range"),
                        rs.getString("band"), rs.getObject("score", Integer.class),
                        rs.getTimestamp("updated_at").toInstant(), rs.getLong("version")))
                .optional();
    }

    @Override
    @Transactional
    public LeadView update(UUID tenantId, UUID leadId, long expectedVersion, LeadUpdateRequest request) {
        var now = Timestamp.from(Instant.now());
        var updated = jdbc.sql("""
                update lead
                   set name = coalesce(:name, name), email = coalesce(:email, email),
                       preferred_vehicle = coalesce(:preferredVehicle, preferred_vehicle),
                       budget_range = coalesce(:budgetRange, budget_range),
                       phone = coalesce(:phone, phone), version = version + 1,
                       updated_at = :updatedAt
                 where id = :leadId and tenant_id = :tenantId and version = :expectedVersion
                """)
                .param("name", request.name()).param("email", request.email())
                .param("preferredVehicle", request.preferredVehicle()).param("budgetRange", request.budgetRange())
                .param("phone", request.phone()).param("leadId", leadId).param("tenantId", tenantId)
                .param("expectedVersion", expectedVersion).param("updatedAt", now).update();
        if (updated != 1) throw new IllegalStateException("lead version is stale");
        if (request.temperature() != null) {
            var normalized = request.temperature().toUpperCase(java.util.Locale.ROOT);
            jdbc.sql("""
                    insert into lead_score(id, lead_id, band, score, reasons, source, created_at)
                    values (:id, :leadId, :band, :score, cast(:reasons as jsonb), :source, :createdAt)
                    """)
                    .param("id", UUID.randomUUID()).param("leadId", leadId).param("band", normalized)
                    .param("score", switch (normalized) { case "HOT" -> 80; case "WARM" -> 50; default -> 20; })
                    .param("reasons", "[\"operator_update\"]")
                    .param("source", request.temperatureSource() == null ? "HUMAN" : request.temperatureSource().toUpperCase(java.util.Locale.ROOT))
                    .param("createdAt", now).update();
        }
        jdbc.sql("""
                insert into audit_event(id, tenant_id, actor_type, actor_id, event_type,
                                        aggregate_type, aggregate_id, metadata, occurred_at)
                values (:id, :tenantId, 'OPERATOR', 'api', 'LEAD_UPDATED',
                        'lead', :leadId, cast(:metadata as jsonb), :occurredAt)
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("leadId", leadId)
                .param("metadata", "{\"source\":\"operator_api\"}").param("occurredAt", now).update();
        return find(tenantId, leadId).orElseThrow(() -> new IllegalStateException("lead disappeared after update"));
    }
}
