package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.api.InventoryQuery;
import com.ford.service.catalog.application.port.out.VehicleCatalogRepository;
import com.ford.service.catalog.domain.InventoryResult;
import com.ford.service.catalog.domain.VehicleDetails;
import com.ford.service.catalog.domain.VehicleSummary;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public final class JdbcVehicleCatalogRepository implements VehicleCatalogRepository {

    private static final Currency MXN = Currency.getInstance("MXN");
    private final JdbcClient jdbc;

    public JdbcVehicleCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<VehicleSummary> search(CatalogQuery query) {
        return jdbc.sql("""
                select v.id, vm.name model_name, v.name variant_name, vm.model_year,
                       coalesce(p.amount, 0) price, coalesce(p.freshness_status, 'UNKNOWN') freshness_status
                from vehicle_variant v
                join vehicle_model vm on vm.id = v.model_id
                left join lateral (
                    select amount, freshness_status from vehicle_price p
                    where p.variant_id = v.id and p.tenant_id = :tenantId
                      and p.effective_from <= current_timestamp
                      and (p.effective_to is null or p.effective_to > current_timestamp)
                    order by p.observed_at desc limit 1
                ) p on true
                where v.tenant_id = :tenantId
                  and (:text is null or lower(vm.name || ' ' || v.name) like lower('%' || :text || '%'))
                  and (:modelYear is null or vm.model_year = :modelYear)
                order by vm.name, v.name
                """)
                .param("tenantId", query.tenantId())
                .param("text", query.text())
                .param("modelYear", query.modelYear())
                .query((rs, rowNum) -> new VehicleSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("model_name"),
                        rs.getString("variant_name"),
                        rs.getInt("model_year"),
                        List.of(),
                        new Money(rs.getBigDecimal("price").setScale(2), MXN),
                        FreshnessStatus.valueOf(rs.getString("freshness_status"))))
                .list();
    }

    @Override
    public Optional<VehicleDetails> findDetails(UUID vehicleId) {
        return jdbc.sql("select id, name, model_year from vehicle_model where id = :id")
                .param("id", vehicleId)
                .query((rs, rowNum) -> new VehicleDetails(
                        rs.getObject("id", UUID.class),
                        new VehicleSummary(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                "",
                                rs.getInt("model_year"),
                                List.of(),
                                new Money(BigDecimal.ZERO.setScale(2), MXN),
                                FreshnessStatus.UNKNOWN),
                        java.util.Map.of()))
                .optional();
    }

    @Override
    public InventoryResult inventory(InventoryQuery query) {
        return jdbc.sql("""
                select count(*) available_units,
                       coalesce(min(freshness_status), 'UNKNOWN') freshness_status,
                       coalesce(max(observed_at), current_timestamp) observed_at
                from vehicle_unit vu
                join vehicle_variant vv on vv.id = vu.variant_id
                where vu.tenant_id = :tenantId and vv.variant_code = :variantCode
                  and (:dealershipCode is null or vu.dealership_code = :dealershipCode)
                  and vu.status = 'AVAILABLE'
                """)
                .param("tenantId", query.tenantId())
                .param("variantCode", query.variantCode())
                .param("dealershipCode", query.dealershipCode())
                .query((rs, rowNum) -> new InventoryResult(
                        rs.getInt("available_units"),
                        FreshnessStatus.valueOf(rs.getString("freshness_status")),
                        rs.getTimestamp("observed_at").toInstant()))
                .single();
    }
}
