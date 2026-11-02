package com.ford.service.conversations.application;

import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Retries old durable conversation publications when the dedicated worker has admission capacity. */
@Component
public class ConversationPublicationRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConversationPublicationRecovery.class);
    private static final long RECOVERY_LOCK_KEY = 4_707_294_181_232L;

    private final IncompleteEventPublications publications;
    private final ThreadPoolTaskExecutor executor;
    private final ConversationPublicationRecoveryPolicy policy;
    private final ConversationProperties properties;
    private final PostgresAdvisoryExecutionLock executionLock;

    public ConversationPublicationRecovery(IncompleteEventPublications publications,
                                           @Qualifier("conversationTurnExecutor") ThreadPoolTaskExecutor executor,
                                           ConversationPublicationRecoveryPolicy policy,
                                           ConversationProperties properties,
                                           PostgresAdvisoryExecutionLock executionLock) {
        this.publications = publications;
        this.executor = executor;
        this.policy = policy;
        this.properties = properties;
        this.executionLock = executionLock;
    }

    @Scheduled(fixedDelayString = "${FORD_CONVERSATION_PUBLICATION_RECOVERY_INTERVAL:PT30S}")
    public void resubmitWhenCapacityReturns() {
        executionLock.runIfAcquired(RECOVERY_LOCK_KEY, this::resubmitWhenCapacityReturnsWithLock);
    }

    private void resubmitWhenCapacityReturnsWithLock() {
        var pool = executor.getThreadPoolExecutor();
        var admissionSlots = pool.getQueue().remainingCapacity()
                + Math.max(0, pool.getMaximumPoolSize() - pool.getActiveCount());
        if (admissionSlots == 0) return;

        var permits = new AtomicInteger(admissionSlots);
        var retryBefore = Instant.now().minus(properties.workflow().publicationRetryAge());
        try {
            publications.resubmitIncompletePublications(publication -> policy.eligible(publication, retryBefore)
                    && permits.getAndUpdate(value -> value > 0 ? value - 1 : value) > 0);
        } catch (RuntimeException exception) {
            log.warn("Conversation publication recovery failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
