package com.ford.service.handoff;

import com.ford.service.conversations.api.InternalHandoffNote;
import com.ford.service.handoff.domain.HandoffPriority;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InternalHandoffTicketTest {
    @Test
    void keepsOperatorNoteSeparateFromFiveFieldBdcPacket() {
        var note = new InternalHandoffNote("specialist_handoff", "NORMAL", List.of(), List.of(),
                List.of("model_year", "trim"), "no_action");
        var ticket = new HandoffTicket(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                HandoffStatus.HUMAN_PENDING, HandoffPriority.NORMAL, null, "handoff", null, 0,
                Instant.parse("2026-10-21T12:00:00Z"), null, null,
                com.ford.service.conversations.api.BdcHandoffPacket.empty(), note);

        assertThat(ticket.internalNote()).isEqualTo(note);
        assertThat(ticket.packet().arguments()).isEmpty();
    }
}
