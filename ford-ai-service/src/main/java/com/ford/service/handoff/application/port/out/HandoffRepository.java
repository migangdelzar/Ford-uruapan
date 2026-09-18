package com.ford.service.handoff.application.port.out;

import com.ford.service.handoff.domain.HandoffTicket;

import java.util.List;
import java.util.UUID;

public interface HandoffRepository {
    HandoffTicket save(HandoffTicket ticket);

    HandoffTicket findRequired(UUID id);

    HandoffTicket findByConversation(UUID conversationId);

    List<HandoffTicket> list(UUID tenantId);
}
