package com.ford.service.offers.application;

import com.ford.service.offers.domain.Offer;

import java.time.Instant;
import java.util.List;

public interface OfferService {
    List<Offer> activeOffers(Instant at);
}
