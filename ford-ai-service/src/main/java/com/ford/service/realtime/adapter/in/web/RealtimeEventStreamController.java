package com.ford.service.realtime.adapter.in.web;

import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/operator/events")
public final class RealtimeEventStreamController {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final RealtimeEventHub hub;

    public RealtimeEventStreamController(RealtimeEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam UUID tenantId) {
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        var registration = new AtomicReference<AutoCloseable>();
        var cleanup = (Runnable) () -> closeQuietly(registration.getAndSet(null));

        var subscription = hub.subscribe(tenantId, event -> send(emitter, event, cleanup));
        registration.set(subscription);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        sendConnected(emitter, tenantId, cleanup);
        return emitter;
    }

    private static void sendConnected(SseEmitter emitter, UUID tenantId, Runnable cleanup) {
        try {
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("connected")
                    .data(Map.of("tenantId", tenantId)));
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
    }

    private static void send(SseEmitter emitter, FrontendEvent event, Runnable cleanup) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id().toString())
                    .name(event.type())
                    .data(event));
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Cleanup must not escape an SSE lifecycle callback.
        }
    }
}
