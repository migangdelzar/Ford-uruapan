package com.ford.service.ai;

import com.ford.service.ai.adapter.in.tool.FinanceEstimateTool;
import com.ford.service.ai.adapter.in.tool.VehicleTools;
import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.application.VehicleCatalog;
import com.ford.service.finance.application.FinanceOperations;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.finance.domain.FinanceEstimate;
import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyToolContextTest {

    @Test
    void vehicleSearchUsesTenantFromToolContext() {
        var tenantId = UUID.randomUUID();
        var catalog = mock(VehicleCatalog.class);
        var tools = new VehicleTools(catalog);

        tools.search("Ranger", 2026, toolContext(tenantId));

        verify(catalog).search(eq(new CatalogQuery(tenantId, "Ranger", 2026, null)));
    }

    @Test
    void financeEstimateLoadsTermsForContextTenantInsteadOfAcceptingModelRates() {
        var tenantId = UUID.randomUUID();
        var termsRepository = mock(FinanceTermsRepository.class);
        var finance = mock(FinanceOperations.class);
        var terms = new FinanceTerms(BigDecimal.ZERO, money("0"), money("0"));
        var expected = new FinanceEstimate(money("1000"), money("60000"), money("60000"), 60, false);
        when(termsRepository.findActive(tenantId, "ranger", 60)).thenReturn(Optional.of(terms));
        when(finance.estimate(org.mockito.ArgumentMatchers.any(), eq(terms))).thenReturn(expected);
        var tools = new FinanceEstimateTool(finance, termsRepository);

        var result = tools.estimate("ranger", new BigDecimal("120000"), new BigDecimal("60000"),
                60, toolContext(tenantId));

        assertThat(result).isEqualTo(expected);
        verify(termsRepository).findActive(tenantId, "ranger", 60);
    }

    private static ToolContext toolContext(UUID tenantId) {
        return new ToolContext(Map.of("tenantId", tenantId.toString(), "locale", "es-MX"));
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), java.util.Currency.getInstance("MXN"));
    }
}
