package com.ford.service.leads.domain;

import java.util.List;

public record LeadScore(LeadScoreBand band, int value, List<String> reasons) {
    public LeadScore {
        reasons = List.copyOf(reasons);
    }
}
