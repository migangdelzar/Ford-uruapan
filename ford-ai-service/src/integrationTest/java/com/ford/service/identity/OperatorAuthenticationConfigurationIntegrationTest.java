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
import org.testcontainers.containers.GenericContainer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
        "ford.security.auth.password-login-email=operator@example.test",
        "ford.security.auth.password-login-password=test-only-password",
        "spring.session.redis.namespace=ford:test:operator-auth"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OperatorAuthenticationConfigurationIntegrationTest.TestBeans.class)
class OperatorAuthenticationConfigurationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ford.security.auth.signing-key-pkcs8-base64", () -> SIGNING_KEY);
        registry.add("ford.security.auth.email-api-key", () -> "test-only-resend-key");
        registry.add("ford.security.auth.email-from", () -> "Ford Test <noreply@example.com>");
    }

    @Test
    void doesNotRegisterASeparateBffOAuthClient() {
        assertThat(clients.findByClientId("ford-web-bff")).isNull();
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
    void passwordLoginFailureReturnsToTheSharedUiRoute() throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        var token = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

        mvc.perform(post("/login")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .param("username", "unknown@example.test").param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?error"));
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
                        .jwt(token -> token.subject(operator.toString())
                                .claim("tenant_ids", java.util.List.of(tenant.toString())))
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

    @Test
    void authenticatesCrmApiCallsWithTheSpringSessionAndInvalidatesItOnLogout() throws Exception {
        var operator = createPasswordOperator();
        var csrf = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        var token = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

        var login = mvc.perform(post("/login")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .param("username", "operator@example.test")
                        .param("password", "test-only-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(operator.id().toString()))
                .andExpect(jsonPath("$.hasPasskey").value(false));
        mvc.perform(get("/api/v1/operator/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(operator.id().toString()));
        var tenantId = operator.tenantIds().iterator().next().toString();
        mvc.perform(get("/api/v1/conversations").param("tenantId", tenantId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/operator/events/stream").param("tenantId", tenantId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        mvc.perform(post("/api/v1/conversations/" + java.util.UUID.randomUUID() + "/messages")
                        .param("tenantId", tenantId)
                        .cookie(sessionCookie, csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .header("If-Match", "\"v1\"")
                        .contentType("application/json")
                        .content("{\"content\":\"Test UI reply\",\"clientMessageId\":\"test-client-message\",\"internalNote\":false}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/auth/logout")
                        .cookie(sessionCookie, csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(sessionCookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshesSessionAuthoritiesFromTheActiveDatabaseIdentity() throws Exception {
        var operator = createPasswordOperator();
        var csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        var token = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        var login = mvc.perform(post("/login")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token)
                        .param("username", "operator@example.test")
                        .param("password", "test-only-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("SESSION");

        jdbc.sql("update operator_identity set enabled = false where id = :id")
                .param("id", operator.id()).update();

        mvc.perform(get("/api/v1/operator/me").cookie(sessionCookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsBearerApiRequestsStatelessAndExemptFromBrowserCsrf() throws Exception {
        var result = mvc.perform(post("/api/v1/conversations/" + java.util.UUID.randomUUID() + "/messages")
                        .with(jwt().jwt(token -> token.subject(java.util.UUID.randomUUID().toString())
                                        .claim("tenant_ids", java.util.List.of(java.util.UUID.randomUUID().toString())))
                                .authorities(new SimpleGrantedAuthority("SCOPE_operator:write")))
                        .header("Idempotency-Key", "test-request")
                        .header("If-Match", "v1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getCookie("SESSION")).isNull();
    }

    private com.ford.service.identity.api.OperatorIdentity createPasswordOperator() {
        var operatorId = java.util.UUID.randomUUID();
        var tenantId = java.util.UUID.randomUUID();
        jdbc.sql("delete from operator_identity where email_normalized = 'operator@example.test'").update();
        jdbc.sql("insert into operator_identity (id, email_normalized, display_name, enabled) values (:id, :email, :name, true)")
                .param("id", operatorId).param("email", "operator@example.test")
                .param("name", "Test Operator").update();
        jdbc.sql("insert into operator_identity_role (operator_id, role) values (:id, 'MANAGER')")
                .param("id", operatorId).update();
        jdbc.sql("insert into operator_identity_tenant (operator_id, tenant_id) values (:id, :tenant)")
                .param("id", operatorId).param("tenant", tenantId).update();
        for (var scope : java.util.List.of("operator:read", "operator:write", "profile", "email")) {
            jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                    .param("id", operatorId).param("scope", scope).update();
        }
        return new com.ford.service.identity.api.OperatorIdentity(operatorId, "operator@example.test", "Test Operator",
                true, java.util.Set.of("MANAGER"), java.util.Set.of(tenantId),
                java.util.Set.of("operator:read", "operator:write", "profile", "email"));
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
