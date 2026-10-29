package com.ford.service.ingestion.adapter.in.scheduling;

import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FordSyncJobTest {

    @Test
    void runsSyncOnlyWhenTheDatabaseGuardIsAcquired() {
        var tenantId = UUID.randomUUID();
        var properties = new FordSyncProperties();
        properties.setTenantId(tenantId);
        var coordinator = mock(SyncCoordinator.class);
        var acquired = new AtomicBoolean();
        var guard = mock(PostgresAdvisoryExecutionLock.class);
        when(guard.runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), any(Runnable.class))).thenAnswer(invocation -> {
            acquired.set(true);
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        new FordSyncJob(coordinator, properties, guard).synchronize();

        assertThat(acquired).isTrue();
        verify(guard).runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), any(Runnable.class));
        verify(coordinator).run(any(SyncRequest.class));
    }

    @Test
    void skipsSyncWhenAnotherAppInstanceOwnsTheDatabaseGuard() {
        var properties = new FordSyncProperties();
        properties.setTenantId(UUID.randomUUID());
        var coordinator = mock(SyncCoordinator.class);
        var guard = mock(PostgresAdvisoryExecutionLock.class);
        when(guard.runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), any(Runnable.class))).thenReturn(false);

        new FordSyncJob(coordinator, properties, guard).synchronize();

        verify(guard).runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), any(Runnable.class));
        verify(coordinator, never()).run(any(SyncRequest.class));
    }
}
