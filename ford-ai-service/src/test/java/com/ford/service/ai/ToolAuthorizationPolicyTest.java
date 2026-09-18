package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuthorizationPolicyTest {

    @Test
    void exposesNoMutationToolsToTheModel() {
        var policy = new ToolAuthorizationPolicy();

        assertThat(policy.readOnlyTools(new AiExecutionContext(UUID.randomUUID(), false)))
                .allMatch(tool -> !tool.getToolDefinition().name().toLowerCase().contains("write"));
    }
}
