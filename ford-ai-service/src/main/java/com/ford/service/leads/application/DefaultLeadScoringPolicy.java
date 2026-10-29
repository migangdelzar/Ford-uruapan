package com.ford.service.leads.application;

import com.ford.service.leads.domain.LeadProfile;
import com.ford.service.leads.domain.LeadScore;
import com.ford.service.leads.domain.LeadScoreBand;

import java.util.ArrayList;

public final class DefaultLeadScoringPolicy implements LeadScoringPolicy {

    @Override
    public LeadScore score(LeadProfile profile, ConversationSignals signals) {
        var score = 0;
        var reasons = new ArrayList<String>();
        if (profile.consentGiven()) {
            score += 20;
            reasons.add("consent_given");
        } else {
            reasons.add("consent_missing");
        }
        if (profile.phone() != null && !profile.phone().isBlank()) {
            score += 15;
            reasons.add("phone_available");
        }
        if (profile.preferredVehicle() != null && !profile.preferredVehicle().isBlank()) {
            score += 15;
            reasons.add("vehicle_preference");
        }
        if (profile.budgetRange() != null && !profile.budgetRange().isBlank()) {
            score += 10;
            reasons.add("budget_range");
        }
        if (signals.financingIntent()) {
            score += 15;
            reasons.add("financing_intent");
        }
        if (signals.inventoryIntent()) {
            score += 10;
            reasons.add("inventory_intent");
        }
        if (signals.purchaseTimeline()) {
            score += 15;
            reasons.add("purchase_timeline");
        }
        if (signals.priceOrOfferIntent()) {
            score += 5;
            reasons.add("price_or_offer_intent");
        }
        if (profile.tradeInInterest()) {
            score += 5;
            reasons.add("trade_in_interest");
        }
        if (signals.explicitHumanRequest()) {
            score += 20;
            reasons.add("explicit_human_request");
        }
        var band = score >= 70 ? LeadScoreBand.HOT : score >= 40 ? LeadScoreBand.WARM : LeadScoreBand.COLD;
        return new LeadScore(band, Math.min(score, 100), reasons);
    }
}
