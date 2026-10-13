package com.ford.service.outbox;

import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxBackgroundJobCoordinationTest {

    @Test
    void deliveryRunsOnlyWhileHoldingTheCrossVersionLock() {
        var publisher = mock(OutboxPublisher.class);
        var lock = executingLock();

        new OutboxDeliveryJob(publisher, lock).deliver();

        verify(lock).runIfAcquired(org.mockito.ArgumentMatchers.eq(OutboxDeliveryJob.LOCK_KEY), any(Runnable.class));
        verify(publisher).deliverOne(any(Instant.class));
    }

    @Test
    void leaseRecoveryUsesTheSameLockAndSkipsWhileDeliveryOwnsIt() {
        var store = mock(OutboxStore.class);
        var lock = mock(PostgresAdvisoryExecutionLock.class);
        when(lock.runIfAcquired(anyLong(), any(Runnable.class))).thenReturn(false);

        new OutboxRecoveryJob(store, lock).reclaimExpired();

        verify(lock).runIfAcquired(org.mockito.ArgumentMatchers.eq(OutboxDeliveryJob.LOCK_KEY), any(Runnable.class));
        verify(store, never()).reclaimExpired(any(Instant.class));
    }

    private static PostgresAdvisoryExecutionLock executingLock() {
        var lock = mock(PostgresAdvisoryExecutionLock.class);
        when(lock.runIfAcquired(anyLong(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        return lock;
    }
}
