package com.ford.service.dealership.adapter.in.web;

import com.ford.service.dealership.application.port.out.DealershipPort;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DealershipController {
    private final DealershipPort dealership;

    public DealershipController(DealershipPort dealership) {
        this.dealership = dealership;
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read') || hasRole('ADMIN')")
    public DealershipPort.InventoryPage inventory(@RequestParam UUID tenantId,
                                                         @RequestParam(required = false) String model,
                                                         @RequestParam(required = false) Integer year,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String branchId,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(defaultValue = "50") int pageSize,
                                                         Authentication authentication) {
        requireTenant(authentication, tenantId);
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
        var items = dealership.inventory(tenantId, new DealershipPort.InventoryFilter(model, year, status, branchId, cursor, pageSize));
        return new DealershipPort.InventoryPage(items, null, false);
    }

    @GetMapping("/vehicles/{vin}")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read') || hasRole('ADMIN')")
    public DealershipPort.VehicleDetails vehicle(@PathVariable String vin, @RequestParam UUID tenantId,
                                                  Authentication authentication) {
        requireTenant(authentication, tenantId);
        return dealership.vehicle(tenantId, vin);
    }

    @PostMapping("/finance/quote")
    @PreAuthorize("hasAuthority('SCOPE_finance:quote') || hasRole('ADMIN')")
    public DealershipPort.FinanceQuote quote(@RequestParam UUID tenantId, @RequestBody FinanceQuoteBody body,
                                              Authentication authentication) {
        requireTenant(authentication, tenantId);
        return dealership.quote(tenantId, new DealershipPort.FinanceQuoteRequest(body.vin(), body.downPayment(), body.termMonths(), body.creditScoreTier()));
    }

    @GetMapping("/appointments/available-slots")
    @PreAuthorize("hasAuthority('SCOPE_appointments:read') || hasRole('ADMIN')")
    public List<DealershipPort.AppointmentSlot> slots(@RequestParam UUID tenantId, @RequestParam String branchId,
                                                       @RequestParam LocalDate date, @RequestParam(required = false) String vin,
                                                       Authentication authentication) {
        requireTenant(authentication, tenantId);
        return dealership.slots(tenantId, branchId, date, vin);
    }

    @PostMapping("/appointments/book")
    @PreAuthorize("hasAuthority('SCOPE_appointments:write') || hasRole('ADMIN')")
    public ResponseEntity<DealershipPort.Appointment> book(@RequestParam UUID tenantId, @RequestBody AppointmentBody body,
                                                             Authentication authentication) {
        requireTenant(authentication, tenantId);
        return ResponseEntity.status(201).body(dealership.book(tenantId,
                new DealershipPort.AppointmentBooking(body.vin(), body.customerPhone(), body.branchId(), body.slotId(), body.advisorId())));
    }

    @PostMapping("/leads/{leadId}/sync")
    @PreAuthorize("hasAuthority('SCOPE_leads:write') || hasRole('ADMIN')")
    public ResponseEntity<DealershipPort.SyncReceipt> sync(@PathVariable UUID leadId, @RequestParam UUID tenantId,
                                                            Authentication authentication) {
        requireTenant(authentication, tenantId);
        return ResponseEntity.accepted().body(dealership.sync(tenantId, leadId));
    }

    @GetMapping("/branches/on-duty")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<DealershipPort.BranchOnDuty> branches(@RequestParam UUID tenantId, Authentication authentication) {
        requireTenant(authentication, tenantId);
        return dealership.branches(tenantId);
    }

    private static void requireTenant(Authentication authentication, UUID tenantId) {
        if (!AuthenticatedOperator.from(authentication).canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
    }

    public record FinanceQuoteBody(String vin, BigDecimal downPayment, int termMonths, String creditScoreTier) { }
    public record AppointmentBody(String vin, String customerPhone, String branchId, String slotId, String advisorId) { }
}
