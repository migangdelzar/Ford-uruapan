package com.ford.service.offers.application;

import com.ford.service.offers.domain.Offer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class DefaultOfferService implements OfferService {

    private final List<Offer> offers;

    public DefaultOfferService(List<Offer> offers) {
        this.offers = List.copyOf(offers);
    }

    @Override
    public List<Offer> activeOffers(Instant at) {
        Objects.requireNonNull(at);
        return offers.stream()
                .filter(offer -> !at.isBefore(offer.effectiveFrom()) && at.isBefore(offer.effectiveTo()))
                .toList();
    }
}
