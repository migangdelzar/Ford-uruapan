package com.ford.service.realtime;

import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeEventHubTest {

    @Test
    void publishesOnlyToSubscribersOfTheSameTenant() {
        var hub = new RealtimeEventHub();
        var tenant = UUID.randomUUID();
        var otherTenant = UUID.randomUUID();
        var received = new ArrayList<FrontendEvent>();
        hub.subscribe(tenant, received::add);

        hub.publish(event(otherTenant));
        hub.publish(event(tenant));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().tenantId()).isEqualTo(tenant);
    }

    @Test
    void closingSubscriptionStopsFutureDelivery() throws Exception {
        var hub = new RealtimeEventHub();
        var tenant = UUID.randomUUID();
        var received = new ArrayList<FrontendEvent>();
        var registration = hub.subscribe(tenant, received::add);

        registration.close();
        hub.publish(event(tenant));

        assertThat(received).isEmpty();
    }

    private static FrontendEvent event(UUID tenantId) {
        return new FrontendEvent(UUID.randomUUID(), tenantId, UUID.randomUUID(), "message.created",
                Instant.now(), Map.of("text", "Hola"));
    }
}
