package com.ford.service.realtime;

import com.ford.service.realtime.adapter.in.web.RealtimeEventStreamController;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeEventStreamControllerTest {

    @Test
    void exposesVersionedOperatorEventsRoute() {
        assertThat(RealtimeEventStreamController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/operator/events");
    }

    @Test
    void registersSseClientForRequestedTenant() {
        var hub = mock(RealtimeEventHub.class);
        var registration = mock(AutoCloseable.class);
        var consumer = new AtomicReference<java.util.function.Consumer<FrontendEvent>>();
        when(hub.subscribe(any(), any())).thenAnswer(invocation -> {
            consumer.set(invocation.getArgument(1));
            return registration;
        });
        var controller = new RealtimeEventStreamController(hub);
        var tenant = UUID.randomUUID();

        var emitter = controller.stream(tenant);

        assertThat(emitter).isNotNull();
        verify(hub).subscribe(eq(tenant), any());
        assertThat(consumer).hasValueSatisfying(value -> value.accept(new FrontendEvent(
                UUID.randomUUID(), tenant, null, "message.created", Instant.now(), Map.of("text", "Hola"))));
    }
}
