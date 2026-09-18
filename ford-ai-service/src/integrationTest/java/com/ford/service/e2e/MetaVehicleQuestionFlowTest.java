package com.ford.service.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.DefaultAssistantOrchestrator;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetaVehicleQuestionFlowTest {

    @Test
    void normalizesSpanishMetaQuestionAndReturnsCatalogGroundedAnswer() throws Exception {
        var tenantId = UUID.randomUUID();
        var fixture = Files.readString(Path.of("src/test/resources/fixtures/meta/whatsapp-message.json"));
        var inbound = new WhatsAppChannelAdapter(new ObjectMapper()).parseInbound(
                new RawWebhook(tenantId, fixture, "fixture:whatsapp"));
        var orchestrator = new DefaultAssistantOrchestrator(new IntentRouter(), new ToolAuthorizationPolicy(),
                (request, context) -> new AssistantResponse(
                        "La Ford Ranger 2026 tiene motor 2.3L y precio vigente desde $589,000 MXN; confirma inventario con la agencia.",
                        false, "test", List.of("catalog:ranger-2026")));

        var response = orchestrator.respond(new AssistantRequest(tenantId, UUID.randomUUID(), inbound.text(),
                "BOT_ACTIVE", false));

        assertThat(inbound.text()).contains("Ranger");
        assertThat(response.content()).contains("2026", "MXN");
        assertThat(response.sourceDocumentIds()).contains("catalog:ranger-2026");
    }
}
