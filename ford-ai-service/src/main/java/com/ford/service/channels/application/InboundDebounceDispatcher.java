package com.ford.service.channels.application;

import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Re-publishes due conversation turns so the existing conversation consumer processes
 * them. Claims are durable, so a worker restart resumes the delayed opportunities.
 */
public class InboundDebounceDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboundDebounceDispatcher.class);

    private final InboundDebounceRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final InboundDebounceProperties properties;

    public InboundDebounceDispatcher(InboundDebounceRepository repository,
                                     ApplicationEventPublisher publisher,
                                     Clock clock,
                                     InboundDebounceProperties properties) {
        this.repository = Objects.requireNonNull(repository);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        this.properties = Objects.requireNonNull(properties);
    }

    @Scheduled(fixedDelayString = "${FORD_INBOUND_DEBOUNCE_POLL_INTERVAL:PT1S}")
    @Transactional
    public void dispatchDue() {
        var due = repository.claimDue(clock.instant(), properties.claimBatchSize());
        for (var event : due) {
            log.info("Inbound debounce released: conversationId={} receiptId={} providerMessageId={}",
                    event.conversationId(), event.receiptId(), event.providerMessageId());
            publisher.publishEvent(InboundTurnReleased.from(event));
        }
    }
}
