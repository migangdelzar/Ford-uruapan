package com.ford.service.conversations;

import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.CustomerHandoffAuthorizationPolicy;
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

/** Synthetic behavior checks for the approved conversation and specialist-consent flows. */
class ChatLeadRoutingEvaluationTest {

    private final CustomerHandoffOfferPolicy offerPolicy = new CustomerHandoffOfferPolicy();
    private final CustomerHandoffAuthorizationPolicy authorization = new CustomerHandoffAuthorizationPolicy();

    @Test
    void greetingAndExploratoryInterestDoNotTriggerSpecialistOffers() {
        var memory = ConversationMemoryContext.empty(key());
        assertThat(offerPolicy.shouldOffer("Hola", "Hola, ¿en qué puedo ayudarle?", LeadTemperature.COLD, memory))
                .isFalse();
        assertThat(offerPolicy.shouldOffer("Apenas exploro modelos Ford.", "Le comparto opciones.",
                LeadTemperature.WARM, memory)).isFalse();
    }

    @Test
    void hotLeadGetsAnOfferButCannotEnterTheQueueWithoutConsent() {
        var memory = ConversationMemoryContext.empty(key());
        var customerMessage = "Me interesa la Ranger";

        assertThat(offerPolicy.shouldOffer(customerMessage, "Puedo revisar opciones.", LeadTemperature.HOT, memory))
                .isTrue();
        assertThat(authorization.isAuthorized(customerMessage, memory)).isFalse();
    }

    @Test
    void affirmativeAfterPersistedOfferAuthorizesExactlyTheConsentStep() {
        var memory = memory(List.of(message(ConversationMessage.Direction.OUTBOUND,
                CustomerHandoffOfferPolicy.OFFER)));

        assertThat(authorization.isAuthorized("Sí, quiero que me conectes con un especialista.", memory)).isTrue();
    }

    @Test
    void rememberedDeclineSuppressesProactiveOfferButDoesNotBlockLaterDirectRequest() {
        var declined = new ConversationMemoryContext(key(), "", List.of(),
                List.of(new MemoryFact("specialist_offer_declined", "true", UUID.randomUUID())), 3);

        assertThat(offerPolicy.shouldOffer("Estoy listo para comprar una Territory.", "Reviso opciones.",
                LeadTemperature.HOT, declined)).isFalse();
        assertThat(authorization.isAuthorized("Quiero hablar con un especialista Ford.", declined)).isTrue();
    }

    @Test
    void conversationsKeepTheirOwnLeadFactsAndOfferState() {
        var one = new ConversationMemoryContext(key(), "", List.of(),
                List.of(new MemoryFact("vehicle_interest", "Ranger", UUID.randomUUID())), 1);
        var two = new ConversationMemoryContext(key(), "", List.of(),
                List.of(new MemoryFact("vehicle_interest", "Territory", UUID.randomUUID())), 1);

        assertThat(one.key().conversationId()).isNotEqualTo(two.key().conversationId());
        assertThat(one.durableFacts()).extracting(MemoryFact::content).containsExactly("Ranger");
        assertThat(two.durableFacts()).extracting(MemoryFact::content).containsExactly("Territory");
    }

    private static ConversationMemoryContext memory(List<ConversationMessage> turns) {
        return new ConversationMemoryContext(key(), "", turns, List.of(), 2);
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "synthetic-account",
                "synthetic-customer", UUID.randomUUID());
    }

    private static ConversationMessage message(ConversationMessage.Direction direction, String content) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), direction,
                UUID.randomUUID().toString(), content, Instant.now());
    }
}
