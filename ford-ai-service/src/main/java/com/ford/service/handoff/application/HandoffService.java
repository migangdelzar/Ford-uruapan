package com.ford.service.handoff.application;

import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffTicket;

import java.util.List;
import java.util.UUID;

public interface HandoffService {
    HandoffTicket request(HandoffRequest request);

    HandoffTicket claim(UUID ticketId, UUID operatorId);

    HandoffTicket pauseBot(UUID conversationId, UUID operatorId);

    HandoffTicket resumeBot(UUID conversationId, UUID operatorId);

    List<HandoffTicket> list(UUID tenantId);
}
