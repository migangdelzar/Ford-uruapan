package com.ford.service.realtime.adapter.in.web;

import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/operator/events")
public class RealtimeEventStreamController {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final Logger log = LoggerFactory.getLogger(RealtimeEventStreamController.class);

    private final RealtimeEventHub hub;

    public RealtimeEventStreamController(RealtimeEventHub hub) {
        this.hub = hub;
    }

    public SseEmitter stream(Authentication authentication) {
        return stream(null, authentication);
    }

    @PreAuthorize("hasAuthority('SCOPE_operator:read')")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) UUID tenantId, Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var resolvedTenantId = tenantId;
        if (resolvedTenantId == null) {
            if (operator.tenantIds().size() != 1) {
                throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
            }
            resolvedTenantId = operator.tenantIds().iterator().next();
        }
        if (!operator.canAccess(resolvedTenantId)) {
            throw new AccessDeniedException("operator is not authorized for tenant");
        }
        var authorizedTenantId = resolvedTenantId;
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        var registration = new AtomicReference<AutoCloseable>();
        var cleanup = (Runnable) () -> closeQuietly(registration.getAndSet(null));

        var subscription = hub.subscribe(authorizedTenantId, event -> send(emitter, event, cleanup));
        registration.set(subscription);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            log.info("Operator event stream closed outcome=timeout");
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> {
            log.warn("Operator event stream closed outcome=error errorType={}",
                    error == null ? "unknown" : error.getClass().getSimpleName());
            cleanup.run();
        });
        log.info("Operator event stream connected");
        sendConnected(emitter, authorizedTenantId, cleanup);
        return emitter;
    }

    private static void sendConnected(SseEmitter emitter, UUID tenantId, Runnable cleanup) {
        try {
            var data = tenantId == null ? Map.of() : Map.of("tenantId", tenantId);
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("connected")
                    .data(data));
        } catch (IOException exception) {
            log.warn("Operator event stream closed outcome=send_failure errorType={}",
                    exception.getClass().getSimpleName());
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
            log.warn("Operator event stream closed outcome=send_failure errorType={}",
                    exception.getClass().getSimpleName());
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
