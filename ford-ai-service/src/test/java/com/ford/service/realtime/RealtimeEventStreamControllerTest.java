package com.ford.service.realtime;

import com.ford.service.realtime.adapter.in.web.RealtimeEventStreamController;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        var emitter = controller.stream(tenant, authentication(tenant));

        assertThat(emitter).isNotNull();
        verify(hub).subscribe(eq(tenant), any());
        assertThat(consumer).hasValueSatisfying(value -> value.accept(new FrontendEvent(
                UUID.randomUUID(), tenant, null, "message.created", Instant.now(), Map.of("text", "Hola"))));
    }

    @Test
    void derivesSingleTenantConnectionFromValidatedToken() {
        var hub = mock(RealtimeEventHub.class);
        when(hub.subscribe(any(), any())).thenReturn(mock(AutoCloseable.class));
        var controller = new RealtimeEventStreamController(hub);
        var tenant = UUID.randomUUID();

        assertThat(controller.stream(authentication(tenant))).isNotNull();
        verify(hub).subscribe(eq(tenant), any());
    }

    @Test
    void rejectsTenantOutsideTheValidatedToken() {
        var controller = new RealtimeEventStreamController(mock(RealtimeEventHub.class));

        assertThatThrownBy(() -> controller.stream(UUID.randomUUID(), authentication(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requiresExplicitTenantWhenTokenHasMultipleTenants() {
        var controller = new RealtimeEventStreamController(mock(RealtimeEventHub.class));

        assertThatThrownBy(() -> controller.stream(authentication(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId is required when operator has multiple tenants");
    }

    private static JwtAuthenticationToken authentication(UUID... tenants) {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("operator-1")
                .claim("tenant_ids", java.util.Arrays.stream(tenants).map(UUID::toString).toList())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_operator:read")));
    }
}
