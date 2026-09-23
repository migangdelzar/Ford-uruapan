package com.ford.service.dealership.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentPort {
    List<DealershipPort.AppointmentSlot> slots(UUID tenantId, String branchId, LocalDate date, String vin);

    DealershipPort.Appointment book(UUID tenantId, DealershipPort.AppointmentBooking request);
}
