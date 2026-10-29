package com.ford.service.ai.application;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

@NamedInterface
public interface AiTestSessionStore {

    List<StoredMessage> recentMessages(UUID tenantId, String operatorSubject, UUID conversationId, int limit);

    void saveTurn(UUID tenantId, String operatorSubject, UUID conversationId, String userMessage,
                  String assistantMessage);

    record StoredMessage(String role, String content) { }
}
