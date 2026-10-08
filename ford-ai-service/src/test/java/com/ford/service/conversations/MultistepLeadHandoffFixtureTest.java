package com.ford.service.conversations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.application.CustomerHandoffAuthorizationPolicy;
import com.ford.service.conversations.application.SpecialistHandoffPayloadFactory;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic authorization-only adaptation of Ford-scraping's ten scripted flows. */
class MultistepLeadHandoffFixtureTest {
    private final CustomerHandoffAuthorizationPolicy policy = new CustomerHandoffAuthorizationPolicy();
    private final SpecialistHandoffPayloadFactory payloadFactory = new SpecialistHandoffPayloadFactory();

    @Test
    void tenFlowsMeetTheCodeEnforcedAuthorizationGate() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/multistep-leads/flows.json")) {
            assertThat(input).as("copied Ford-scraping evaluation fixture").isNotNull();
            var root = new ObjectMapper().readTree(input);
            var results = new ArrayList<String>();
            for (var flow : root.path("flows")) {
                var id = flow.path("id").asText();
                var turns = flow.path("userTurns");
                var latest = turns.get(turns.size() - 1).asText();
                var expected = flow.path("expected").path("handoff").asText().equals("required");
                var memory = priorTurns(turns, id);
                var actual = policy.isAuthorized(latest, memory);
                assertThat(actual).as("flow %s authorization, deterministic only", id).isEqualTo(expected);
                var answer = switch (id) {
                    case "conflicting-offer-terms" -> "El plazo del nombre no coincide con el plazo registrado.";
                    case "unavailable-offer-validity" -> "La vigencia no está confirmada.";
                    default -> "Información consultada.";
                };
                var expectedNames = new java.util.LinkedHashSet<String>();
                flow.path("expected").path("payloadFields").forEach(value -> expectedNames.add(value.asText()));
                var actualNames = actual ? payloadFactory.create(latest, memory, answer).arguments().keySet()
                        : java.util.Set.<String>of();
                assertThat(actualNames).as("flow %s strict payload fields", id)
                        .isEqualTo(expectedNames);
                assertThat(flow.path("expected").path("resolutionAttemptsMax").asInt())
                        .as("flow %s resolution-attempt bound", id).isLessThanOrEqualTo(1);
                results.add(id + "=" + (actual ? "authorized" : "not-authorized"));
            }
            assertThat(results).hasSize(10);
        }
    }

    @Test
    void aBareLaterYesNeedsAnImmediatelyPrecedingAssistantOffer() {
        var offered = memory(List.of(
                message(ConversationMessage.Direction.INBOUND, "Quiero comprar una Territory."),
                message(ConversationMessage.Direction.OUTBOUND, "¿Desea que le conecte con un especialista Ford?"),
                message(ConversationMessage.Direction.INBOUND, "Sí")));
        var noOffer = memory(List.of(message(ConversationMessage.Direction.INBOUND, "Sí")));
        assertThat(policy.isAuthorized("Sí", offered)).isTrue();
        assertThat(policy.isAuthorized("Sí", noOffer)).isFalse();
    }

    private static ConversationMemoryContext priorTurns(JsonNode turns, String flowId) {
        var messages = new ArrayList<ConversationMessage>();
        if (turns.size() > 1) {
            messages.add(message(ConversationMessage.Direction.INBOUND, turns.get(0).asText()));
            if (!flowId.equals("declined-specialist")) {
                messages.add(message(ConversationMessage.Direction.OUTBOUND,
                        "¿Desea que le conecte con un especialista Ford?") );
            }
            messages.add(message(ConversationMessage.Direction.INBOUND, turns.get(turns.size() - 1).asText()));
        }
        return memory(messages);
    }

    private static ConversationMemoryContext memory(List<ConversationMessage> messages) {
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                UUID.randomUUID(), com.ford.service.channels.domain.Channel.WHATSAPP,
                "eval", "synthetic-customer", UUID.randomUUID());
        return new ConversationMemoryContext(key, "", messages, List.of(), 1);
    }

    private static ConversationMessage message(ConversationMessage.Direction direction, String content) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), direction,
                UUID.randomUUID().toString(), content, Instant.now());
    }
}
