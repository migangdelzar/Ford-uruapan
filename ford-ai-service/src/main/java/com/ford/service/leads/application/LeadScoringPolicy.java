package com.ford.service.leads.application;

import com.ford.service.leads.domain.LeadProfile;
import com.ford.service.leads.domain.LeadScore;

public interface LeadScoringPolicy {
    LeadScore score(LeadProfile profile, ConversationSignals signals);
}
