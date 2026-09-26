package com.ford.service.followups.application;

import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.followups.domain.FollowUpConsent;
import com.ford.service.followups.domain.LeadFollowUp;
import com.ford.service.followups.api.LeadFollowUpTurnTracker;
import com.ford.service.shared.ConversationTurnCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LeadFollowUpService implements LeadFollowUpTurnTracker {
    private final LeadFollowUpRepository repository;
    private final WarmFollowUpPolicy policy;

    public LeadFollowUpService(LeadFollowUpRepository repository) {
        this.repository = repository;
        this.policy = new WarmFollowUpPolicy();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @ApplicationModuleListener
    public void on(ConversationTurnCompleted event) {
        processTurn(event.tenantId(), event.conversationId(), event.providerMessageId(),
                event.leadTemperature(), event.inboundText(), event.assistantText());
    }

    @Transactional
    public void processTurn(UUID tenantId, UUID conversationId, String inboundMessageId,
                            String leadTemperature, String inboundText, String assistantText) {
        var temperature = LeadTemperature.valueOf(leadTemperature);
        var current = repository.find(tenantId, conversationId);
        if (current.isEmpty()) {
            if (policy.isOffer(temperature, assistantText)) {
                repository.offer(tenantId, conversationId, inboundMessageId);
            }
            return;
        }
        if (current.get().status() != LeadFollowUp.Status.OFFERED) return;
        var consent = policy.consent(inboundText);
        if (consent == FollowUpConsent.ACCEPT) repository.accept(tenantId, conversationId, inboundMessageId);
        if (consent == FollowUpConsent.DECLINE) repository.decline(tenantId, conversationId);
    }

    @Transactional(readOnly = true)
    public List<LeadFollowUp> queued(UUID tenantId) {
        return repository.queued(tenantId);
    }

    @Transactional
    public boolean claim(UUID tenantId, UUID followUpId) {
        return repository.claim(tenantId, followUpId);
    }
}
