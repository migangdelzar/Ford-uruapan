package com.ford.service.ai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogToolArchitectureTest {

    @Test
    void usesInProcessSpringAiToolsWithoutRemoteMcpClientDependency() throws Exception {
        var build = Files.readString(Path.of("build.gradle.kts"));
        var versionCatalog = Files.readString(Path.of("gradle/libs.versions.toml"));
        var configuration = Files.readString(Path.of(
                "src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java"));
        var properties = Files.readString(Path.of(
                "src/main/java/com/ford/service/ai/configuration/CatalogToolProperties.java"));
        var yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(build).doesNotContain("libs.spring.ai.starter.mcp.client");
        assertThat(versionCatalog).doesNotContain("spring-ai-starter-mcp-client");
        assertThat(configuration).doesNotContain("SyncMcpToolCallbackProvider");
        assertThat(configuration).doesNotContain("McpClientCustomizer");
        assertThat(yaml).doesNotContain("FORD_MCP_URL");
        assertThat(yaml).doesNotContain("FORD_MCP_TOKEN");
        assertThat(yaml).contains("FORD_CATALOG_TOOLS_ENABLED:false");
        assertThat(configuration).contains("CatalogTools");
    }
}
