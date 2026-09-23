package com.ford.service.dealership.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DealershipPort extends InventoryPort, FinanceQuotePort, AppointmentPort, LeadSyncPort {
    List<BranchOnDuty> branches(UUID tenantId);

    record InventoryFilter(String model, Integer year, String status, String branchId,
                           String cursor, int pageSize) { }
    record InventoryItem(UUID vehicleId, String vin, String model, String trim, Integer year, String status,
                         String branchId, BigDecimal priceMxn) { }
    record InventoryPage(List<InventoryItem> items, String nextCursor, boolean hasNext) {
        public InventoryPage { items = List.copyOf(items == null ? List.of() : items); }
    }
    record VehicleDetails(String vin, String model, Integer year, Map<String, String> specifications,
                          BigDecimal priceMxn, List<String> photoUrls) { }
    record FinanceQuoteRequest(String vin, BigDecimal downPayment, int termMonths, String creditScoreTier) { }
    record FinanceQuote(String vin, String currency, BigDecimal monthlyPayment, BigDecimal apr,
                        int termMonths, boolean insuranceIncluded, String disclaimer) { }
    record AppointmentSlot(String slotId, String branchId, Instant startsAt, Instant endsAt, String advisorId) { }
    record AppointmentBooking(String vin, String customerPhone, String branchId, String slotId, String advisorId) { }
    record Appointment(String appointmentId, String vin, String branchId, String slotId, String status,
                       String calendarInvitationUrl) { }
    record SyncReceipt(String syncId, String status) { }
    record BranchOnDuty(String branchId, String name, List<String> advisorIds) { }
}
