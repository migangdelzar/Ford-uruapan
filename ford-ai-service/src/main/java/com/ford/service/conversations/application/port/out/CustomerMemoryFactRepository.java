package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.CustomerShoppingFact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CustomerMemoryFactRepository {
    FactUpdateResult upsert(CustomerShoppingFact fact);

    boolean hasProcessedSource(UUID identityId, UUID sourceReceiptId);

    List<CustomerShoppingFact> findCurrent(UUID identityId, Instant now, int limit);

    ForgetResult forget(UUID identityId, Instant forgottenAt, UUID auditId, String actorId);

    default ForgetResult forget(UUID identityId, Instant forgottenAt, UUID auditId) {
        return forget(identityId, forgottenAt, auditId, null);
    }

    void reopen(UUID identityId, com.ford.service.conversations.domain.CustomerShoppingFactType type,
                String subjectKey, UUID sourceReceiptId, Instant reopenedAt);

    record FactUpdateResult(boolean changed, long version) { }
    record ForgetResult(int removedFactCount, Instant factsForgetBefore) { }
}
