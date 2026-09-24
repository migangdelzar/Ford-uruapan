package com.ford.service.realtime.application;

import com.ford.service.realtime.domain.FrontendEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public final class RealtimeEventHub {

    private final ConcurrentHashMap<UUID, Subscriber> subscribers = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(UUID tenantId, Consumer<FrontendEvent> consumer) {
        if (tenantId == null || consumer == null) {
            throw new IllegalArgumentException("tenantId and consumer are required");
        }
        var id = UUID.randomUUID();
        subscribers.put(id, new Subscriber(tenantId, consumer));
        return () -> subscribers.remove(id);
    }

    public void publish(FrontendEvent event) {
        subscribers.forEach((id, subscriber) -> {
            if (!subscriber.tenantId().equals(event.tenantId())) {
                return;
            }
            try {
                subscriber.consumer().accept(event);
            } catch (RuntimeException exception) {
                subscribers.remove(id, subscriber);
            }
        });
    }

    private record Subscriber(UUID tenantId, Consumer<FrontendEvent> consumer) {
    }
}
