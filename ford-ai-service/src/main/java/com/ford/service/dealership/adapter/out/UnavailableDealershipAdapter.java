package com.ford.service.dealership.adapter.out;

import com.ford.service.dealership.application.port.out.DealershipPort;
import com.ford.service.shared.UpstreamUnavailableException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class UnavailableDealershipAdapter implements DealershipPort {
    private UpstreamUnavailableException unavailable() {
        return new UpstreamUnavailableException("dealership API is not configured");
    }

    @Override public List<InventoryItem> inventory(UUID tenantId, InventoryFilter filter) { throw unavailable(); }
    @Override public VehicleDetails vehicle(UUID tenantId, String vin) { throw unavailable(); }
    @Override public FinanceQuote quote(UUID tenantId, FinanceQuoteRequest request) { throw unavailable(); }
    @Override public List<AppointmentSlot> slots(UUID tenantId, String branchId, LocalDate date, String vin) { throw unavailable(); }
    @Override public Appointment book(UUID tenantId, AppointmentBooking request) { throw unavailable(); }
    @Override public SyncReceipt sync(UUID tenantId, UUID leadId) { throw unavailable(); }
    @Override public List<BranchOnDuty> branches(UUID tenantId) { throw unavailable(); }
}
