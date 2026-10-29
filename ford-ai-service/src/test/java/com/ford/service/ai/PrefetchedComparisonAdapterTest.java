package com.ford.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.ai.api.ResponseEvidenceStatus;
import com.ford.service.ai.api.ResponseKind;
import com.ford.service.ai.application.*;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class PrefetchedComparisonAdapterTest {
    private static final String PAYLOAD = """
            {"model":"RANGER","year":2027,"trims":[
              {"trim":"XL Gasolina 4x2","price":775000,"currency":"MXN","priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XL"},
              {"trim":"XLT Gasolina 4x2","price":907000,"currency":"MXN","priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XLT"}],
             "comparisons":[{"leftTrim":"XL Gasolina 4x2","rightTrim":"XLT Gasolina 4x2",
               "priceDifferenceMxn":132000,"differences":[]}],"missingTrims":[]}
            """;

    @Test
    void usesCurrentComparisonWithoutForcingUnrelatedGetVehicleAfterModelToolOmission() {
        var fixture = new Fixture("La diferencia de precio publicada entre las versiones es $132,000 MXN.");
        var request = request();
        var response = fixture.adapter.complete(request, context(request));

        assertThat(response.content()).contains("132,000");
        assertThat(response.evidenceStatus()).isEqualTo(ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
        verify(fixture.spec, times(1)).call();
        assertThat(fixture.forcedVehicleCalls).hasValue(0);
    }

    @Test
    void promptMarkerAloneDoesNotSatisfyRequiredRetrieval() {
        var fixture = new Fixture("Puedo revisar los datos de las versiones.");
        var original = request();
        var request = new AssistantRequest(original.tenantId(), original.conversationId(), original.message(),
                "BOT_ACTIVE", "[REPLAY PREFETCHED COMPARISON EVIDENCE]" + PAYLOAD, false, original.decision());

        assertThatThrownBy(() -> fixture.adapter.complete(request,
                new ToolContext(request.tenantId(), request.conversationId())))
                .isInstanceOf(AssistantTurnUnavailableException.class);
        assertThat(fixture.forcedVehicleCalls).hasValue(1);
    }

    @Test
    void wrongRequestEnvelopeDoesNotSkipRequiredToolFallback() {
        var fixture = new Fixture("Puedo revisar los datos de las versiones.");
        var original = request();
        var copy = new AssistantRequest(original.tenantId(), original.conversationId(), original.message(),
                "BOT_ACTIVE", null, false, original.decision());

        assertThatThrownBy(() -> fixture.adapter.complete(copy, context(original)))
                .isInstanceOf(AssistantTurnUnavailableException.class);
        assertThat(fixture.forcedVehicleCalls).hasValue(1);
    }

    @Test
    void validPrefetchDoesNotBypassUnsupportedFactRepair() {
        var fixture = new Fixture("La Ranger 2027 XLT tiene 999999 caballos de fuerza.");
        var request = request();

        var response = fixture.adapter.complete(request, context(request));

        assertThat(response.content()).doesNotContain("999999");
        assertThat(response.responseKind()).isEqualTo(ResponseKind.CLARIFICATION);
        verify(fixture.spec, times(2)).call();
        assertThat(fixture.forcedVehicleCalls).hasValue(0);
    }

    private static ToolContext context(AssistantRequest request) {
        return new ToolContext(request.tenantId(), request.conversationId()).withPrefetchedComparison(
                PrefetchedComparisonEvidence.liveComparison(request, "a".repeat(64), "RANGER", 2027,
                        List.of("XL Gasolina 4x2", "XLT Gasolina 4x2"), PAYLOAD));
    }

    private static AssistantRequest request() {
        var evidence = new ClassificationEvidence(ClassifierType.INTENT, "VEHICLE_DETAILS", 1.0,
                "rules_intent", "rules", "deterministic", 0, true);
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.COLD,
                LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, List.of(evidence));
        return new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué características confirmadas distinguen a la Ranger XLT de la XL Gasolina 4x2?",
                "BOT_ACTIVE", null, false, decision.withVehicleEntities(new VehicleEntityResolver().resolve(
                "¿Qué características confirmadas distinguen a la Ranger XLT de la XL Gasolina 4x2?", null)));
    }

    private static final class Fixture {
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        final AtomicInteger forcedVehicleCalls = new AtomicInteger();
        final SpringAiChatCompletionAdapter adapter;

        Fixture(String answer) {
            var client = mock(ChatClient.class);
            var response = mock(ChatClient.CallResponseSpec.class);
            when(client.prompt()).thenReturn(spec);
            when(spec.system(any(String.class))).thenReturn(spec);
            when(spec.user(any(String.class))).thenReturn(spec);
            when(spec.advisors(org.mockito.ArgumentMatchers
                    .<java.util.function.Consumer<ChatClient.AdvisorSpec>>any())).thenReturn(spec);
            when(spec.tools(any(Object[].class))).thenReturn(spec);
            when(spec.toolContext(anyMap())).thenReturn(spec);
            when(spec.call()).thenReturn(response);
            when(response.content()).thenReturn(answer);
            adapter = new SpringAiChatCompletionAdapter(client, "ollama", new ToolAuthorizationPolicy(List.of(
                    callback("compare_trims", null), callback("get_vehicle", forcedVehicleCalls))),
                    new DecisionPolicy(), null, new AssistantResponseQualityPolicy(), new ObjectMapper(),
                    new DefaultAiObservationService(ObservationRegistry.NOOP), null, true, null,
                    new EvidenceGroundedResponsePolicy());
        }

        private static ToolCallback callback(String name, AtomicInteger count) {
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
                }
                @Override public String call(String input) {
                    if (count != null) count.incrementAndGet();
                    return "{}";
                }
            };
        }
    }
}
