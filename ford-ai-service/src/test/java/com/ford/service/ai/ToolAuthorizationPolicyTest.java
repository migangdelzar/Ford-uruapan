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
