package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.ObservedToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedToolCallbackTest {

    @Test
    void delegatesToolDefinitionMetadataAndContextualCalls() {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String input, ToolContext context) {
                return "promotion-result";
            }

            @Override
            public String call(String input) {
                return "promotion-result";
            }
        };
        var callback = new ObservedToolCallback(delegate);

        assertThat(callback.getToolDefinition().name()).isEqualTo("list_promotions");
        assertThat(callback.getToolMetadata()).isEqualTo(delegate.getToolMetadata());
        assertThat(callback.call("{}", new ToolContext(Map.of("tenantId", "tenant"))))
                .isEqualTo("promotion-result");
    }

    @Test
    void propagatesToolFailuresForProviderHandling() {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("source unavailable");
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate).call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source unavailable");
    }
}
