package com.ford.service.dealership.application.port.out;

import java.util.UUID;

public interface FinanceQuotePort {
    DealershipPort.FinanceQuote quote(UUID tenantId, DealershipPort.FinanceQuoteRequest request);
}
