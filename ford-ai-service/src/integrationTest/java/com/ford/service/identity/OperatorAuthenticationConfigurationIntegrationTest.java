package com.ford.service.identity;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.identity.application.OneTimeTokenRequestLimiter;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.modulith.runtime.verification-enabled=false",
        "ford.security.auth.enabled=true",
        "ford.security.jwt.enabled=false",
        "ford.outbox.enabled=false",
        "ford.conversations.workflow.graph-enabled=false",
        "ford.security.auth.csrf-cookie-secure=false",
        "spring.session.redis.namespace=ford:test:operator-auth"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OperatorAuthenticationConfigurationIntegrationTest.TestBeans.class)
class OperatorAuthenticationConfigurationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    static final String SIGNING_KEY = createSigningKey();

    @Autowired MockMvc mvc;
    @Autowired RegisteredClientRepository clients;
    @Autowired JdbcClient jdbc;

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        OneTimeTokenRequestLimiter testOneTimeTokenRequestLimiter() {
            return normalizedEmail -> true;
        }
    }

    @BeforeAll
    static void migrate() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ford.security.auth.signing-key-pkcs8-base64", () -> SIGNING_KEY);
        registry.add("ford.security.auth.bff-client-secret", () -> "test-only-bff-secret");
        registry.add("ford.security.auth.email-api-key", () -> "test-only-resend-key");
        registry.add("ford.security.auth.email-from", () -> "Ford Test <noreply@example.com>");
    }

    @Test
    void registersOnlyThePkceBffClientWithExactRedirectUri() {
        var client = clients.findByClientId("ford-web-bff");

        assertThat(client).isNotNull();
        assertThat(client.getRedirectUris()).containsExactly("https://ford.emme.business/api/auth/callback");
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getAuthorizationGrantTypes()).extracting(org.springframework.security.oauth2.core.AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "refresh_token");
    }

    @Test
    void publishesJwksAndKeepsApiBearerProtected() throws Exception {
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("ford-operator-rsa-1"));
        mvc.perform(get("/api/v1/metrics/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesSpringPasskeyLoginPageForAuthorizationRedirects() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/login/webauthn.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"passkey-signin\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/auth/one-time-token")));
        mvc.perform(get("/login/webauthn.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/webauthn/authenticate/options")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/login/webauthn")));
    }

    @Test
    void returnsOnlyTheCurrentRegistryOperatorWhenIdentityScopesAllowIt() throws Exception {
        var operator = java.util.UUID.randomUUID();
        var tenant = java.util.UUID.randomUUID();
        jdbc.sql("insert into operator_identity (id, email_normalized, display_name, enabled) values (:id, :email, :name, true)")
                .param("id", operator).param("email", "profile-" + operator + "@example.com")
                .param("name", "Profile Operator").update();
        jdbc.sql("insert into operator_identity_role (operator_id, role) values (:id, 'MANAGER')")
                .param("id", operator).update();
        jdbc.sql("insert into operator_identity_tenant (operator_id, tenant_id) values (:id, :tenant)")
                .param("id", operator).param("tenant", tenant).update();
        jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                .param("id", operator).param("scope", "operator:read").update();
        jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                .param("id", operator).param("scope", "profile").update();
        jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                .param("id", operator).param("scope", "email").update();

        mvc.perform(get("/api/v1/operator/me").with(jwt()
                        .jwt(token -> token.subject(operator.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_operator:read"),
                                new SimpleGrantedAuthority("SCOPE_profile"),
                                new SimpleGrantedAuthority("SCOPE_email"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(operator.toString()))
                .andExpect(jsonPath("$.displayName").value("Profile Operator"))
                .andExpect(jsonPath("$.email").value("profile-" + operator + "@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("MANAGER"))
                .andExpect(jsonPath("$.tenantIds[0]").value(tenant.toString()));

        mvc.perform(get("/api/v1/operator/me").with(jwt()
                        .jwt(token -> token.subject(operator.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_operator:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deniesRealtimeEventsForTenantOutsideValidatedToken() throws Exception {
        var assignedTenant = java.util.UUID.randomUUID();
        var requestedTenant = java.util.UUID.randomUUID();

        mvc.perform(get("/api/v1/operator/events/stream")
                        .param("tenantId", requestedTenant.toString())
                        .with(jwt()
                                .jwt(token -> token.subject(java.util.UUID.randomUUID().toString())
                                        .claim("tenant_ids", java.util.List.of(assignedTenant.toString())))
                                .authorities(new SimpleGrantedAuthority("SCOPE_operator:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void providesCsrfTokenForBrowserPasskeyAndOttRequests() throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andReturn();
        var token = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/auth/one-time-token")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .param("email", "not-registered@example.com"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/one-time-token")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .param("username", "not-registered@example.com"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.sql("select count(*) from one_time_tokens").query(Long.class).single()).isZero();
    }

    @Test
    void logsOutTheSpringOperatorSessionWithCsrfProtection() throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        var token = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

        mvc.perform(post("/logout")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isNoContent());
    }

    private static String createSigningKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
