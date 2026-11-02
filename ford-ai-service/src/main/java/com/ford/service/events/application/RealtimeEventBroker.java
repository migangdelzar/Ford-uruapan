package com.ford.service.events.application;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RealtimeEventBroker {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID tenantId) {
        var emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.computeIfAbsent(tenantId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(tenantId, emitter));
        emitter.onTimeout(() -> remove(tenantId, emitter));
        emitter.onError(ignored -> remove(tenantId, emitter));
        return emitter;
    }

    public void publish(UUID tenantId, String eventType, Object data) {
        var current = subscribers.getOrDefault(tenantId, new CopyOnWriteArrayList<>());
        for (var emitter : current) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(data));
            } catch (IOException exception) {
                remove(tenantId, emitter);
            }
        }
    }

    private void remove(UUID tenantId, SseEmitter emitter) {
        var current = subscribers.get(tenantId);
        if (current != null) {
            current.remove(emitter);
            if (current.isEmpty()) subscribers.remove(tenantId, current);
        }
    }
}
