package com.ford.service.offers;

import com.ford.service.offers.application.DefaultOfferService;
import com.ford.service.offers.application.OfferService;
import com.ford.service.offers.domain.Offer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfferServiceTest {

    @Test
    void onlyReturnsOffersActiveAtTheRequestedTime() {
        var now = Instant.parse("2026-09-18T18:00:00Z");
        OfferService service = new DefaultOfferService(List.of(
                new Offer(UUID.randomUUID(), "VIGENTE", "Bono", now.minusSeconds(60), now.plusSeconds(60)),
                new Offer(UUID.randomUUID(), "FUTURA", "Bono futuro", now.plusSeconds(1), now.plusSeconds(120)),
                new Offer(UUID.randomUUID(), "VENCIDA", "Bono vencido", now.minusSeconds(120), now.minusSeconds(1))));

        assertThat(service.activeOffers(now)).extracting(Offer::code).containsExactly("VIGENTE");
    }
}
