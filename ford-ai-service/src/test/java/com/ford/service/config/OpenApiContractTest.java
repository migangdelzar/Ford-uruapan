package com.ford.service.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("docs/openapi/frontend-api.yaml");

    @Test
    void definesTheCanonicalVersionedFrontendContract() throws Exception {
        var yaml = Files.readString(CONTRACT);

        assertThat(yaml).startsWith("openapi: 3.1.0");
        assertThat(yaml).contains("  /api/v1/health:");
        assertThat(yaml).contains("  /api/v1/ai/respond:");
        assertThat(yaml).contains("  /api/v1/conversations:");
        assertThat(yaml).contains("  /api/v1/metrics/overview:");
        assertThat(yaml).contains("  /api/v1/events:");
        assertThat(yaml).contains("  /api/v1/operator/events/stream:");
        assertThat(yaml).contains("  /api/v1/operator/me:");
        assertThat(yaml).doesNotContain("  /api/operator/");
    }

    @Test
    void documentsPasskeyStatusOnlyOnTheBrowserSessionEndpoint() throws Exception {
        var yaml = Files.readString(CONTRACT);
        var browserSession = yaml.substring(yaml.indexOf("  /api/auth/me:"), yaml.indexOf("  /api/auth/logout:"));
        var bearerProfile = yaml.substring(yaml.indexOf("  /api/v1/operator/me:"), yaml.indexOf("  /api/v1/conversations:"));

        assertThat(browserSession).contains("#/components/schemas/BrowserOperatorSession");
        assertThat(bearerProfile).contains("#/components/schemas/OperatorSession");
        assertThat(yaml).contains("    BrowserOperatorSession:", "        hasPasskey:");
        var bearerSchema = yaml.substring(yaml.indexOf("    OperatorSession:"), yaml.indexOf("    BrowserOperatorSession:"));
        assertThat(bearerSchema).doesNotContain("hasPasskey");
    }

    @Test
    void doesNotDescribeRawAiDiagnosticsToTheNormalFrontend() throws Exception {
        var yaml = Files.readString(CONTRACT);

        assertThat(yaml).doesNotContain("systemPromptSent:");
        assertThat(yaml).doesNotContain("rawResponse:");
        assertThat(yaml).doesNotContain("contextWindowSent:");
    }

    @Test
    void handoffContractMatchesControllerQueryParameters() throws Exception {
        var yaml = Files.readString(CONTRACT);
        var takeover = yaml.substring(yaml.indexOf("/api/v1/conversations/{conversationId}/takeover:"),
                yaml.indexOf("/api/v1/conversations/{conversationId}/handback:"));
        var handback = yaml.substring(yaml.indexOf("/api/v1/conversations/{conversationId}/handback:"),
                yaml.indexOf("/api/v1/conversations/{conversationId}/window:"));

        assertThat(takeover).contains("$ref: '#/components/parameters/TenantId'");
        assertThat(handback).contains("$ref: '#/components/parameters/ExpectedVersion'")
                .doesNotContain("HandbackRequest");
    }

    @Test
    void declaresSpringAuthorizationServerAsThePasskeyAndOneTimeTokenIssuer() throws Exception {
        var yaml = Files.readString(CONTRACT);

        assertThat(yaml).contains("providerType: spring-authorization-server");
        assertThat(yaml).contains("issuer: https://ford.emme.business");
        assertThat(yaml).contains("passkeyPrimary: true");
        assertThat(yaml).contains("emailOneTimeTokenFallback: true");
        assertThat(yaml).contains("recommendedDeployment: same_origin_spring_proxy");
        assertThat(yaml).contains("registrationOptions: /webauthn/register/options");
        assertThat(yaml).contains("csrf: /api/auth/csrf");
        assertThat(yaml).contains("generate: /api/auth/one-time-token");
        assertThat(yaml).contains("  /api/auth/me:");
        assertThat(yaml).contains("  /api/auth/logout:");
        assertThat(yaml).contains("name: SESSION");
        assertThat(yaml).doesNotContain("external OIDC provider");
    }
}
