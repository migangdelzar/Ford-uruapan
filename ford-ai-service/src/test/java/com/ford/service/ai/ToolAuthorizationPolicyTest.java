package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import org.springframework.ai.tool.ToolCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.Set;

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
    void exposesFordMcpToolsOnlyToConfiguredTenant() {
        var fordTenant = UUID.randomUUID();
        var otherTenant = UUID.randomUUID();
        var policy = new ToolAuthorizationPolicy(
                List.of(callback("search_vehicles"), callback("vehicle_snapshot_search")), fordTenant);

        assertThat(policy.readOnlyTools(new AiExecutionContext(fordTenant, false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("search_vehicles");
        assertThat(policy.readOnlyTools(new AiExecutionContext(otherTenant, false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("vehicle_snapshot_search");
    }

    @Test
    void removesDuplicateLegacySearchWhenMcpIsReadyForFordTenant() {
        var fordTenant = UUID.randomUUID();
        var policy = new ToolAuthorizationPolicy(
                List.of(callback("search_vehicles"), callback("vehicle_snapshot_search")), fordTenant);

        assertThat(policy.readOnlyTools(new AiExecutionContext(fordTenant, false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("search_vehicles");
    }

    @Test
    void exposesOnlyTheGeneralVehicleSearchWhenNoIntentWasClassified() {
        var fordTenant = UUID.randomUUID();
        var policy = new ToolAuthorizationPolicy(
                List.of(callback("search_vehicles"), callback("get_vehicle"), callback("list_promotions")),
                fordTenant);

        assertThat(policy.readOnlyTools(new AiExecutionContext(fordTenant, false)))
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("search_vehicles");
    }

    private static ToolCallback callback(String name) {
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
                return "{}";
            }
        };
    }
}
