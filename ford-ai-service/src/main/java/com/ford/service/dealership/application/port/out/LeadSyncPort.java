package com.ford.service.dealership.application.port.out;

import java.util.UUID;

public interface LeadSyncPort {
    DealershipPort.SyncReceipt sync(UUID tenantId, UUID leadId);
}
