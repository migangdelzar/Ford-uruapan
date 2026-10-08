package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.ObservedToolCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.SocketTimeoutException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
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
        var callback = new ObservedToolCallback(delegate, new SimpleMeterRegistry());

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

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source unavailable");
    }

    @Test
    void recordsToolOutcomeWithoutLoggingArgumentsOrResults(CapturedOutput output) {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "private-promotion-result";
            }
        };

        var registry = new SimpleMeterRegistry();
        var callback = new ObservedToolCallback(delegate, registry);
        assertThat(callback.call("private-tool-arguments"))
                .isEqualTo("private-promotion-result");

        assertThat(output.getOut())
                .contains("MCP tool call started")
                .contains("tool=list_promotions")
                .contains("MCP tool call completed")
                .contains("outcome=success")
                .doesNotContain("private-tool-arguments")
                .doesNotContain("private-promotion-result");
        assertThat(registry.get("ford.ai.tool.attempt")
                .tags("tool", "finance", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void classifiesAndCountsToolFailureWithoutLoggingCauseMessage(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("outer private value",
                        new SocketTimeoutException("private endpoint and query"));
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, registry).call("private arguments"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.getOut())
                .contains("failureCategory=timeout")
                .contains("rootCauseType=SocketTimeoutException")
                .doesNotContain("outer private value")
                .doesNotContain("private endpoint and query")
                .doesNotContain("private arguments");
        assertThat(registry.get("ford.ai.tool.attempt")
                .tags("tool", "catalog", "outcome", "failure", "failure_category", "timeout")
                .timer().count()).isEqualTo(1);
    }
}
