package com.ford.service.conversations;

import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.CustomerHandoffOfferPolicy;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerHandoffOfferPolicyTest {
    private final CustomerHandoffOfferPolicy policy = new CustomerHandoffOfferPolicy();

    @Test
    void offersAfterClearPurchaseAndAppointmentTriggers() {
        assertThat(policy.shouldOffer("Estoy listo para comprar una Territory este mes.",
                "Hay opciones disponibles.")).isTrue();
        assertThat(policy.shouldOffer("Me interesa hacer una prueba de manejo de la Bronco Sport.",
                "Puedo revisar la información disponible.")).isTrue();
    }

    @Test
    void offersAfterAnUnresolvedOfferDisclosureButNotAResolvedOrRoutineQuestion() {
        assertThat(policy.shouldOffer("¿Sigue vigente la oferta?",
                "La vigencia no está confirmada.")).isTrue();
        assertThat(policy.shouldOffer("¿Qué características tiene la Territory?",
                "La Territory incluye las especificaciones consultadas.")).isFalse();
        assertThat(policy.shouldOffer("Apenas estoy explorando modelos Ford.",
                "Le comparto opciones disponibles.")).isFalse();
    }

    @Test
    void offersSpecialistHelpToHotLeadsWithoutQueueingThem() {
        assertThat(policy.shouldOffer("Me interesa la Ranger", "Aquí tiene información.",
                LeadTemperature.HOT, ConversationMemoryContext.empty(key()))).isTrue();
    }

    @Test
    void suppressesProactiveOffersAfterTheCustomerDeclines() {
        var memory = new ConversationMemoryContext(key(), "", List.of(),
                List.of(new MemoryFact("specialist_offer_declined", "true", UUID.randomUUID())), 1);

        assertThat(policy.shouldOffer("Estoy listo para comprar una Territory este mes.",
                "Hay opciones disponibles.", LeadTemperature.HOT, memory)).isFalse();
    }

    @Test
    void directRequestsAreNotRecastAsConsentOffers() {
        assertThat(policy.shouldOffer("Quiero hablar con un especialista Ford.",
                "Claro, lo comunico.")).isFalse();
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "provider", "participant",
                UUID.randomUUID());
    }
}
