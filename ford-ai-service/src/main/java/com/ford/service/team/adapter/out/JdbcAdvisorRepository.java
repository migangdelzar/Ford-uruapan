package com.ford.service.team.adapter.out;

import com.ford.service.team.api.AdvisorView;
import com.ford.service.team.application.AdvisorQueryService.AdvisorRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAdvisorRepository implements AdvisorRepository {
    private final JdbcClient jdbc;

    public JdbcAdvisorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AdvisorView> list(UUID tenantId, String currentOperatorSubject) {
        var currentOperatorId = UUID.nameUUIDFromBytes(currentOperatorSubject.getBytes(StandardCharsets.UTF_8));
        var values = new ArrayList<>(jdbc.sql("""
                select assigned_operator_id as operator_id,
                       count(*) filter (where status = 'HUMAN_ACTIVE') as active_count,
                       count(*) as assigned_count
                  from handoff_ticket
                 where tenant_id = :tenantId and assigned_operator_id is not null
                 group by assigned_operator_id
                 order by active_count desc, assigned_count desc, assigned_operator_id
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new AdvisorView(
                        rs.getObject("operator_id", UUID.class), null, null, "operator", null,
                        rs.getLong("active_count") > 0
                                ? AdvisorView.Status.IN_MEETING : AdvisorView.Status.AVAILABLE,
                        null, rs.getLong("assigned_count")))
                .list());
        if (values.stream().noneMatch(value -> value.id().equals(currentOperatorId))) {
            values.add(new AdvisorView(currentOperatorId, null, null, "operator", null,
                    AdvisorView.Status.AVAILABLE, null, 0));
        }
        return List.copyOf(values);
    }
}
