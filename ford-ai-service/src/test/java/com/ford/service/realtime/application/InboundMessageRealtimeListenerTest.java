package com.ford.service.realtime.application;

import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.realtime.domain.FrontendEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboundMessageRealtimeListenerTest {
    @Test
    void realtimeTextIsSanitizedEvenIfRepositoryReturnsContactText() throws Exception {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var repository = mock(AcceptedInboundMessageRepository.class);
        var intake = mock(AlternateContactIntakePort.class);
        var hub = new RealtimeEventHub();
        var received = new AtomicReference<FrontendEvent>();
        try (var ignored = hub.subscribe(tenant, received::set)) {
            var raw = "Correo privado@example.com";
            var safe = "Correo [dato de contacto omitido]";
            when(repository.find(tenant, conversation, receipt)).thenReturn(Optional.of(
                    new AcceptedInboundMessage("wamid.1", "participant", raw, Instant.EPOCH)));
            when(intake.redact(raw)).thenReturn(safe);
            var listener = new InboundMessageRealtimeListener(hub, repository, intake);

            listener.on(new InboundMessageAccepted(receipt, tenant, conversation, Channel.WHATSAPP,
                    "wamid.1", Instant.EPOCH));

            assertThat(received.get().data()).containsEntry("text", safe);
            assertThat(received.get().toString()).doesNotContain("private@example.com");
        }
    }
}
