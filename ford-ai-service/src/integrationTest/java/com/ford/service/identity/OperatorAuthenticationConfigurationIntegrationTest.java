package com.ford.service.identity;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.application.OneTimeTokenEmailSender;
import com.ford.service.identity.application.OneTimeTokenRequestLimiter;
import jakarta.servlet.http.Cookie;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.util.UriComponentsBuilder;
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
    @Autowired CapturingEmailSender emailSender;

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        OneTimeTokenRequestLimiter testOneTimeTokenRequestLimiter() {
            return normalizedEmail -> true;
        }

        @Bean
        @Primary
        CapturingEmailSender testOneTimeTokenEmailSender() {
            return new CapturingEmailSender();
        }
    }

    private record Delivery(String recipient, String magicLink) {}

    private record Csrf(Cookie cookie, String token) {}

    private static final class CapturingEmailSender implements OneTimeTokenEmailSender {
        private final java.util.List<Delivery> deliveries = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void send(String recipient, String magicLink) {
            deliveries.add(new Delivery(recipient, magicLink));
        }

        Delivery latestFor(String recipient) {
            return deliveries.stream()
                    .filter(delivery -> delivery.recipient().equals(recipient))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("No one-time-token email captured for " + recipient));
        }

        boolean hasDeliveryFor(String recipient) {
            return deliveries.stream().anyMatch(delivery -> delivery.recipient().equals(recipient));
        }

        void clear() {
            deliveries.clear();
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

    @BeforeEach
    void clearCapturedEmail() {
        emailSender.clear();
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
                .andExpect(jsonPath("$.operatorId").value(operator.id().toString()));
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
    void completesOneTimeTokenLoginForEverySupportedRole() throws Exception {
        for (var role : java.util.List.of("OPERATOR", "MANAGER", "ADMIN")) {
            var scopes = role.equals("ADMIN")
                    ? java.util.Set.of("profile", "email")
                    : java.util.Set.of("operator:read", "profile", "email");
            var identity = createIdentity(role, true, scopes);
            var session = requestAndRedeemOneTimeToken(identity.email());

            mvc.perform(get("/api/auth/me").cookie(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operatorId").value(identity.id().toString()))
                    .andExpect(jsonPath("$.roles[0]").value(role))
                    .andExpect(jsonPath("$.tenantIds[0]")
                            .value(identity.tenantIds().iterator().next().toString()));
        }
    }

    @Test
    void oneTimeTokenRequestsAreNeutralForActiveDisabledAndUnknownIdentities() throws Exception {
        var active = createIdentity("OPERATOR", true, java.util.Set.of("operator:read"));
        var disabled = createIdentity("MANAGER", false, java.util.Set.of("operator:read"));
        var unknownEmail = "unknown-" + java.util.UUID.randomUUID() + "@example.test";
        var tokensBefore = jdbc.sql("select count(*) from one_time_tokens").query(Long.class).single();

        var activeResponse = requestOneTimeToken(active.email());
        var disabledResponse = requestOneTimeToken(disabled.email());
        var unknownResponse = requestOneTimeToken(unknownEmail);

        assertThat(activeResponse.getResponse().getStatus()).isEqualTo(202);
        assertThat(disabledResponse.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknownResponse.getResponse().getStatus()).isEqualTo(202);
        assertThat(activeResponse.getResponse().getContentAsString()).isEqualTo("{\"accepted\":true}");
        assertThat(disabledResponse.getResponse().getContentAsString()).isEqualTo("{\"accepted\":true}");
        assertThat(unknownResponse.getResponse().getContentAsString()).isEqualTo("{\"accepted\":true}");
        assertThat(emailSender.hasDeliveryFor(active.email())).isTrue();
        assertThat(emailSender.hasDeliveryFor(disabled.email())).isFalse();
        assertThat(emailSender.hasDeliveryFor(unknownEmail)).isFalse();
        assertThat(jdbc.sql("select count(*) from one_time_tokens where username = :email")
                .param("email", active.email())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from one_time_tokens").query(Long.class).single())
                .isEqualTo(tokensBefore + 1);
    }

    @Test
    void passwordLoginIsRestrictedToTheConfiguredAccount() throws Exception {
        var configured = createPasswordOperator();
        var operator = createIdentity("OPERATOR", true, java.util.Set.of("operator:read"));
        var admin = createIdentity("ADMIN", true, java.util.Set.of("profile"));

        var configuredLogin = passwordLogin(configured.email(), "test-only-password")
                .andExpect(status().is3xxRedirection())
                .andReturn();
        var configuredSession = configuredLogin.getResponse().getCookie("SESSION");
        assertThat(configuredSession).isNotNull();
        mvc.perform(get("/api/auth/me").cookie(configuredSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("MANAGER"));

        for (var identity : java.util.List.of(operator, admin)) {
            var rejected = passwordLogin(identity.email(), "test-only-password")
                    .andExpect(status().is3xxRedirection())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .redirectedUrl("/login?error"))
                    .andReturn();
            var rejectedSession = rejected.getResponse().getCookie("SESSION");
            var sessionRequest = get("/api/auth/me");
            if (rejectedSession != null) sessionRequest.cookie(rejectedSession);
            mvc.perform(sessionRequest).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void sessionAuthorizationUsesScopesAdminOverrideAndTenantMembership() throws Exception {
        for (var role : java.util.List.of("OPERATOR", "MANAGER", "ADMIN")) {
            var scopes = role.equals("ADMIN")
                    ? java.util.Set.of("profile", "email")
                    : java.util.Set.of("operator:read", "profile", "email");
            var identity = createIdentity(role, true, scopes);
            var session = requestAndRedeemOneTimeToken(identity.email());
            var assignedTenant = identity.tenantIds().iterator().next();
            var otherTenant = java.util.UUID.randomUUID();

            mvc.perform(get("/api/v1/team/advisors")
                            .param("tenantId", assignedTenant.toString())
                            .cookie(session))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/team/advisors")
                            .param("tenantId", otherTenant.toString())
                            .cookie(session))
                    .andExpect(status().isForbidden());
        }

        var managerWithoutReadScope = createIdentity("MANAGER", true, java.util.Set.of("profile", "email"));
        var session = requestAndRedeemOneTimeToken(managerWithoutReadScope.email());
        mvc.perform(get("/api/v1/team/advisors")
                        .param("tenantId", managerWithoutReadScope.tenantIds().iterator().next().toString())
                        .cookie(session))
                .andExpect(status().isForbidden());
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

    private OperatorIdentity createIdentity(String role, boolean enabled, java.util.Set<String> scopes) {
        var operatorId = java.util.UUID.randomUUID();
        var tenantId = java.util.UUID.randomUUID();
        var email = role.toLowerCase(java.util.Locale.ROOT) + "-" + operatorId + "@example.test";
        jdbc.sql("insert into operator_identity (id, email_normalized, display_name, enabled) values (:id, :email, :name, :enabled)")
                .param("id", operatorId).param("email", email).param("name", "Test " + role).param("enabled", enabled)
                .update();
        jdbc.sql("insert into operator_identity_role (operator_id, role) values (:id, :role)")
                .param("id", operatorId).param("role", role).update();
        jdbc.sql("insert into operator_identity_tenant (operator_id, tenant_id) values (:id, :tenant)")
                .param("id", operatorId).param("tenant", tenantId).update();
        for (var scope : scopes) {
            jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                    .param("id", operatorId).param("scope", scope).update();
        }
        return new OperatorIdentity(operatorId, email, "Test " + role, enabled,
                java.util.Set.of(role), java.util.Set.of(tenantId), scopes);
    }

    private org.springframework.test.web.servlet.MvcResult requestOneTimeToken(String email) throws Exception {
        var csrf = csrf();
        return mvc.perform(post("/api/auth/one-time-token")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .param("email", email))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private Cookie requestAndRedeemOneTimeToken(String email) throws Exception {
        requestOneTimeToken(email);
        var delivery = emailSender.latestFor(email);
        var token = UriComponentsBuilder.fromUriString(delivery.magicLink())
                .build().getQueryParams().getFirst("token");
        assertThat(token).isNotBlank();
        var csrf = csrf();
        var login = mvc.perform(post("/login/ott")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .param("token", token))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        var session = login.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private org.springframework.test.web.servlet.ResultActions passwordLogin(String email, String password) throws Exception {
        var csrf = csrf();
        return mvc.perform(post("/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .param("username", email)
                        .param("password", password));
    }

    private Csrf csrf() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), token);
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
