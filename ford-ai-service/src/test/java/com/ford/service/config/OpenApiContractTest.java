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
        assertThat(yaml).doesNotContain("  /api/operator/");
    }

    @Test
    void doesNotDescribeRawAiDiagnosticsToTheNormalFrontend() throws Exception {
        var yaml = Files.readString(CONTRACT);

        assertThat(yaml).doesNotContain("systemPromptSent:");
        assertThat(yaml).doesNotContain("rawResponse:");
        assertThat(yaml).doesNotContain("contextWindowSent:");
    }

    @Test
    void declaresSpringAuthorizationServerAsThePasskeyAndOneTimeTokenIssuer() throws Exception {
        var yaml = Files.readString(CONTRACT);

        assertThat(yaml).contains("providerType: spring-authorization-server");
        assertThat(yaml).contains("issuer: https://ford.emme.business");
        assertThat(yaml).contains("passkeyPrimary: true");
        assertThat(yaml).contains("emailOneTimeTokenFallback: true");
        assertThat(yaml).contains("registrationOptions: /webauthn/register/options");
        assertThat(yaml).contains("generate: /ott/generate");
        assertThat(yaml).doesNotContain("external OIDC provider");
    }
}
