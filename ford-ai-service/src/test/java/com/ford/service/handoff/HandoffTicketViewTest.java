package com.ford.service.handoff;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.handoff.api.HandoffTicketView;
import com.ford.service.handoff.domain.HandoffPriority;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import com.ford.service.handoffcontact.api.PreferredContactChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffTicketViewTest {
    @Test
    void ordinaryTicketDtoOmitsContactConsentAndChannelPreference() {
        var contact = new AlternateContactValue(ContactType.EMAIL, "private@example.com", Instant.EPOCH,
                "receipt-id", "v1");
        var packet = new BdcHandoffPacket(Optional.of("Ranger"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(contact), Optional.of(PreferredContactChannel.ALTERNATE_EMAIL));
        var ticket = new HandoffTicket(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                HandoffStatus.HUMAN_PENDING, HandoffPriority.NORMAL, null, "CUSTOMER_REQUEST", null, 0,
                Instant.EPOCH, null, null, packet);

        var view = HandoffTicketView.from(ticket);

        assertThat(view.packet()).containsEntry("vehicleModel", "Ranger");
        assertThat(view.packet()).doesNotContainKeys("alternateContactType", "alternateContactValue",
                "alternateContactConsentedAt", "alternateContactMessageId", "alternateContactPolicyVersion",
                "preferredContactChannel");
        assertThat(view.toString()).doesNotContain("private@example.com", "receipt-id");
    }
}
