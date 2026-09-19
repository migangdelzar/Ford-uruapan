package com.ford.service.handoff.application;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffTicket;

import java.util.List;
import java.util.UUID;

public interface HandoffService {
    HandoffTicket request(HandoffRequest request, OperatorPrincipal principal);

    HandoffTicket claim(UUID ticketId, long expectedVersion, OperatorPrincipal principal);

    HandoffTicket pauseBot(UUID conversationId, long expectedVersion, OperatorPrincipal principal);

    HandoffTicket resumeBot(UUID conversationId, long expectedVersion, OperatorPrincipal principal);

    List<HandoffTicket> list(OperatorPrincipal principal);
}
