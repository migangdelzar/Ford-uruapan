package com.ford.service.finance.adapter.out;

import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.finance.domain.AvailableFinancePlan;
import com.ford.service.shared.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcFinanceTermsRepository implements FinanceTermsRepository {

    private static final Currency MXN = Currency.getInstance("MXN");
    private final JdbcClient jdbc;

    public JdbcFinanceTermsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FinanceTerms> findActive(UUID tenantId, String productCode, int termMonths) {
        return jdbc.sql("""
                select coalesce(t.annual_rate, p.annual_rate) annual_rate,
                       p.opening_fee, p.insurance_fee
                from finance_product p
                left join finance_term t on t.finance_product_id = p.id
                    and t.months = :termMonths
                where p.tenant_id = :tenantId
                  and p.product_code = :productCode
                  and p.effective_from <= current_timestamp
                  and (p.effective_to is null or p.effective_to > current_timestamp)
                """)
                .param("tenantId", tenantId)
                .param("productCode", productCode)
                .param("termMonths", termMonths)
                .query((rs, rowNum) -> new FinanceTerms(
                        rs.getBigDecimal("annual_rate"),
                        new Money(rs.getBigDecimal("opening_fee"), MXN),
                        new Money(rs.getBigDecimal("insurance_fee"), MXN)))
                .optional();
    }

    @Override
    public List<AvailableFinancePlan> findAvailable(UUID tenantId) {
        return jdbc.sql("""
                select p.lender_code, p.product_code, p.name, t.months,
                       coalesce(t.annual_rate, p.annual_rate) annual_rate
                from finance_product p
                join finance_term t on t.finance_product_id = p.id
                where p.tenant_id = :tenantId
                  and p.effective_from <= current_timestamp
                  and (p.effective_to is null or p.effective_to > current_timestamp)
                order by p.name, t.months
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new AvailableFinancePlan(
                        rs.getString("lender_code"), rs.getString("product_code"), rs.getString("name"),
                        rs.getInt("months"), rs.getBigDecimal("annual_rate")))
                .list();
    }
}
