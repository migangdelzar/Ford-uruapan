package com.ford.service.channels;

import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.handoffcontact.api.StagingContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelMessageServicePrivacyTest {
    @Test
    void redactsBeforePersistenceThenStagesUsingPersistedConversationAndReceiptIds() {
        var deduplication = mock(InboundMessageDeduplicationPort.class);
        var persistence = mock(InboundMessagePersistencePort.class);
        var intake = mock(AlternateContactIntakePort.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = new ChannelMessageService(deduplication, persistence, publisher, intake);
        var tenant = UUID.randomUUID();
        var parsedConversation = UUID.randomUUID();
        var persistedConversation = UUID.randomUUID();
        var receivedAt = Instant.parse("2026-10-23T19:00:00Z");
        var raw = "Autorizo compartir mi número con Ford: 55 1234 5678";
        var message = new InboundMessage(tenant, Channel.WHATSAPP, "account", "provider-1", "participant-1",
                raw, List.of(), receivedAt, "meta:whatsapp:opaque", UUID.randomUUID(), parsedConversation);
        when(deduplication.recordIfNew(any(InboundMessage.class), any(UUID.class))).thenReturn(true);
        when(intake.redact(raw)).thenReturn("Autorizo compartir mi número con Ford: [dato de contacto omitido]");
        when(persistence.persist(any(InboundMessage.class), any(UUID.class))).thenReturn(persistedConversation);

        var accepted = service.acceptAll(List.of(message));

        assertThat(accepted).singleElement().extracting(InboundMessage::text)
                .isEqualTo("Autorizo compartir mi número con Ford: [dato de contacto omitido]");
        var order = inOrder(intake, persistence);
        order.verify(intake).redact(raw);
        var saved = org.mockito.ArgumentCaptor.forClass(InboundMessage.class);
        var receipt = org.mockito.ArgumentCaptor.forClass(UUID.class);
        order.verify(persistence).persist(saved.capture(), receipt.capture());
        assertThat(saved.getValue().text()).doesNotContain("55 1234 5678");
        order.verify(intake).captureAndStage(new StagingContext(tenant, persistedConversation, receipt.getValue()),
                raw, receivedAt);
        verify(publisher).publishEvent(any(Object.class));
    }
}
