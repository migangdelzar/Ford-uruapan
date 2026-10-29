package com.ford.service.deployment;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.conversations.application.ConversationProperties;
import com.ford.service.conversations.application.ConversationPublicationRecovery;
import com.ford.service.conversations.application.ConversationPublicationRecoveryPolicy;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.ingestion.adapter.in.scheduling.FordSyncJob;
import com.ford.service.ingestion.adapter.in.scheduling.FordSyncProperties;
import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.outbox.OutboxDeliveryJob;
import com.ford.service.outbox.OutboxPublisher;
import com.ford.service.outbox.OutboxRecoveryJob;
import com.ford.service.outbox.OutboxStore;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class BlueGreenBackgroundWorkSafetyIntegrationTest {

    private static final long TEST_LOCK_KEY = 4_707_294_181_299L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    @Test
    void onlyOneOverlappingAppVersionRunsTheGuardedSyncAndLockIsReleasedAfterwards() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var oldVersion = new PostgresAdvisoryExecutionLock(dataSource);
        var candidateVersion = new PostgresAdvisoryExecutionLock(dataSource);
        var executions = new AtomicInteger();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> oldVersion.runIfAcquired(TEST_LOCK_KEY, () -> {
                executions.incrementAndGet();
                firstEntered.countDown();
                await(releaseFirst);
            }));

            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var secondAcquired = candidateVersion.runIfAcquired(TEST_LOCK_KEY, executions::incrementAndGet);
            assertThat(secondAcquired).isFalse();
            assertThat(executions).hasValue(1);

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(candidateVersion.runIfAcquired(TEST_LOCK_KEY, executions::incrementAndGet)).isTrue();
            assertThat(executions).hasValue(2);
        }
    }

    @Test
    void releasesTheSessionLockWhenGuardedWorkThrows() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var oldVersion = new PostgresAdvisoryExecutionLock(dataSource);
        var candidateVersion = new PostgresAdvisoryExecutionLock(dataSource);

        assertThatThrownBy(() -> oldVersion.runIfAcquired(TEST_LOCK_KEY, () -> {
            throw new IllegalArgumentException("expected test failure");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(candidateVersion.runIfAcquired(TEST_LOCK_KEY, () -> { })).isTrue();
    }

    @Test
    void overlappingOldAndCandidateSyncJobsCreateOneSyncRun() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var executions = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldCoordinator = mock(SyncCoordinator.class);
        var candidateCoordinator = mock(SyncCoordinator.class);
        when(oldCoordinator.run(any())).thenAnswer(invocation -> {
            executions.incrementAndGet();
            entered.countDown();
            await(release);
            return null;
        });
        var properties = new FordSyncProperties();
        properties.setTenantId(UUID.randomUUID());
        var oldJob = new FordSyncJob(oldCoordinator, properties, new PostgresAdvisoryExecutionLock(dataSource));
        var candidateJob = new FordSyncJob(candidateCoordinator, properties, new PostgresAdvisoryExecutionLock(dataSource));

        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(oldJob::synchronize);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            candidateJob.synchronize();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }

        assertThat(executions).hasValue(1);
        verify(candidateCoordinator, never()).run(any());
    }

    @Test
    void overlappingOldAndCandidateOutboxJobsDispatchOneDueEvent() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldPublisher = mock(OutboxPublisher.class);
        var candidatePublisher = mock(OutboxPublisher.class);
        when(oldPublisher.deliverOne(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return true;
        });
        var oldJob = new OutboxDeliveryJob(oldPublisher, new PostgresAdvisoryExecutionLock(dataSource));
        var candidateJob = new OutboxDeliveryJob(candidatePublisher, new PostgresAdvisoryExecutionLock(dataSource));

        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(oldJob::deliver);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            candidateJob.deliver();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }

        assertThat(calls).hasValue(1);
        verify(candidatePublisher, never()).deliverOne(any());
    }

    @Test
    void overlappingOldAndCandidatePublicationRecoveryJobsResubmitOnce() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldPublications = mock(IncompleteEventPublications.class);
        var candidatePublications = mock(IncompleteEventPublications.class);
        doAnswer(invocation -> {
            entered.countDown();
            await(release);
            return null;
        }).when(oldPublications).resubmitIncompletePublications(org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
        var oldExecutor = taskExecutor();
        var candidateExecutor = taskExecutor();
        var properties = new ConversationProperties();
        var policy = new ConversationPublicationRecoveryPolicy(mock(ConversationRepository.class));
        var oldJob = new ConversationPublicationRecovery(oldPublications, oldExecutor, policy, properties,
                new PostgresAdvisoryExecutionLock(dataSource));
        var candidateJob = new ConversationPublicationRecovery(candidatePublications, candidateExecutor, policy, properties,
                new PostgresAdvisoryExecutionLock(dataSource));

        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(oldJob::resubmitWhenCapacityReturns);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            candidateJob.resubmitWhenCapacityReturns();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            oldExecutor.shutdown();
            candidateExecutor.shutdown();
        }

        verify(oldPublications).resubmitIncompletePublications(org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
        verify(candidatePublications, never()).resubmitIncompletePublications(
                org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
    }

    @Test
    void outboxLeaseRecoveryWaitsForAnInFlightDeliveryFromEitherVersion() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldStore = mock(OutboxStore.class);
        var candidateStore = mock(OutboxStore.class);
        when(oldStore.reclaimExpired(any())).thenAnswer(invocation -> {
            entered.countDown();
            await(release);
            return 0;
        });
        var oldJob = new OutboxRecoveryJob(oldStore, new PostgresAdvisoryExecutionLock(dataSource));
        var candidateJob = new OutboxRecoveryJob(candidateStore, new PostgresAdvisoryExecutionLock(dataSource));

        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(oldJob::reclaimExpired);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            candidateJob.reclaimExpired();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }

        verify(oldStore).reclaimExpired(any());
        verify(candidateStore, never()).reclaimExpired(any());
    }

    private static ThreadPoolTaskExecutor taskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test coordination interrupted", exception);
        }
    }
}
