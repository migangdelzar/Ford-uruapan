package com.ford.service.events.adapter.in.web;

import com.ford.service.events.api.AuditEventPage;
import com.ford.service.events.application.AuditEventQueryService;
import com.ford.service.events.application.RealtimeEventBroker;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@PreAuthorize("hasAuthority('SCOPE_events:read') || hasRole('ADMIN')")
public class EventController {
    private final AuditEventQueryService audit;
    private final RealtimeEventBroker broker;

    public EventController(AuditEventQueryService audit, RealtimeEventBroker broker) {
        this.audit = audit;
        this.broker = broker;
    }

    @GetMapping
    public AuditEventPage list(@RequestParam(required = false) String cursor,
                               @RequestParam(defaultValue = "50") int pageSize,
                               @RequestParam(name = "type", required = false) String eventType,
                               @RequestParam(required = false) UUID tenantId,
                               Authentication authentication) {
        return audit.list(tenant(authentication, tenantId), cursor, pageSize, eventType);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) UUID tenantId,
                             Authentication authentication) {
        var emitter = broker.subscribe(tenant(authentication, tenantId));
        try {
            emitter.send(SseEmitter.event().name("ready").data("connected"));
        } catch (java.io.IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private static UUID tenant(Authentication authentication, UUID requested) {
        var operator = AuthenticatedOperator.from(authentication);
        if (requested != null) {
            if (!operator.canAccess(requested)) throw new SecurityException("operator is not authorized for tenant");
            return requested;
        }
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator.tenantIds().iterator().next();
    }
}
