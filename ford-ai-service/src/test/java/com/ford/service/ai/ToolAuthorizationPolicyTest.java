package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.ToolResultTracker;
import org.springframework.ai.tool.ToolCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuthorizationPolicyTest {

    @Test
    void exposesNoMutationToolsToTheModel() {
        ToolCallback readOnly = callback("vehicle_snapshot");
        ToolCallback mutation = callback("send_message");
        var policy = new ToolAuthorizationPolicy(List.of(readOnly, mutation));

        assertThat(policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("vehicle_snapshot");
    }

    @Test
    void rejectsActionToolsEvenWhenTheyLookLikeReadOnlyCallbacks() {
        var policy = new ToolAuthorizationPolicy(List.of(
                callback("book_appointment"), callback("purchase_vehicle"),
                callback("pay_invoice"), callback("send_message"), callback("get_vehicle")));

        assertThat(policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("get_vehicle");
    }

    @Test
    void appliesTheClassifiedToolAllowListAfterReadOnlyFiltering() {
        ToolCallback finance = callback("finance_estimate");
        ToolCallback vehicle = callback("vehicle_snapshot_search");
        var policy = new ToolAuthorizationPolicy(List.of(finance, vehicle));

        var allowed = policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false),
                Set.of("finance_estimate"));

        assertThat(allowed).extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("finance_estimate");
    }

    @Test
    void allowsOnlyOneCallPerInformationToolInAnAssistantTurn() {
        var calls = new AtomicInteger();
        var delegate = callback("search_offers", calls);
        var policy = new ToolAuthorizationPolicy(List.of(delegate));
        var tool = policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false)).getFirst();

        assertThat(tool.call("{}" )).isEqualTo("{}");
        assertThat(tool.call("{\"different\":true}" )).contains("ya se intentó en este turno");
        assertThat(calls).hasValue(1);
    }

    @Test
    void recordsOnlyNonEmptyResultsFromAuthorizedReadOnlyTools() {
        var tracker = new ToolResultTracker();
        var policy = new ToolAuthorizationPolicy(List.of(callback("search_vehicles")));
        var tool = policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false),
                Set.of("search_vehicles"), tracker).getFirst();

        assertThat(tool.call("{}")).isEqualTo("{}");
        assertThat(tracker.hasReturnedResult()).isTrue();
        assertThat(tracker.returnedToolNames()).containsExactly("search_vehicles");
        assertThat(tracker.returnedPayloads()).containsEntry("search_vehicles", "{}");
    }

    @Test
    void capturesComparisonAndPageLinkEvidenceButNotUnrelatedToolPayloads() {
        var tracker = new ToolResultTracker();
        var policy = new ToolAuthorizationPolicy(List.of(
                callbackReturning("get_variant_comparisons", "{\"comparisonUrl\":\"https://forduruapan.mx/x\"}"),
                callbackReturning("calculate_financing", "sensitive or large output")));
        var tools = policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false), null, tracker);

        tools.getFirst().call("{}");
        tools.get(1).call("{}");

        assertThat(tracker.returnedPayloads()).containsKey("get_variant_comparisons")
                .doesNotContainKey("calculate_financing");
    }

    @Test
    void distinguishesEmptyAndFailedToolCallsFromReturnedData() {
        var emptyTracker = new ToolResultTracker();
        var emptyTool = new ToolAuthorizationPolicy(List.of(callbackReturning("search_vehicles", " ")))
                .readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false), Set.of("search_vehicles"),
                        emptyTracker).getFirst();
        emptyTool.call("{}");
        assertThat(emptyTracker.hasAttempted()).isTrue();
        assertThat(emptyTracker.hasReturnedResult()).isFalse();
        assertThat(emptyTracker.hasFailed()).isFalse();

        var failedTracker = new ToolResultTracker();
        var failedTool = new ToolAuthorizationPolicy(List.of(callbackThrowing("search_vehicles")))
                .readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false), Set.of("search_vehicles"),
                        failedTracker).getFirst();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failedTool.call("{}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failedTracker.hasAttempted()).isTrue();
        assertThat(failedTracker.hasFailed()).isTrue();
        assertThat(failedTracker.hasReturnedResult()).isFalse();
    }

    private static ToolCallback callback(String name) {
        return callback(name, new AtomicInteger());
    }

    private static ToolCallback callback(String name, AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                return "{}";
            }
        };
    }

    private static ToolCallback callbackReturning(String name, String value) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return value;
            }
        };
    }

    private static ToolCallback callbackThrowing(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("source unavailable");
            }
        };
    }
}
