package com.ford.service.channels.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import org.springframework.modulith.events.ApplicationModuleListener;

import java.util.Objects;

/**
 * Records the durable per-conversation debounce schedule for every accepted inbound
 * message. The message is already persisted before this runs, so a burst coalesces
 * into one delayed response opportunity without losing the original receipts.
 */
public class InboundDebounceScheduler {

    private final InboundDebounceService service;

    public InboundDebounceScheduler(InboundDebounceService service) {
        this.service = Objects.requireNonNull(service);
    }

    @ApplicationModuleListener
    public void on(InboundMessageAccepted event) {
        service.schedule(event);
    }
}
