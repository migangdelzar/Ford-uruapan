package com.ford.service.ai.application;

public interface ConversationDecisionPort {
    ConversationDecision decide(ClassificationContext context);
}
