package com.ford.service.identity;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.identity.adapter.persistence.jdbc.JdbcOperatorIdentityRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcOperatorIdentityRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    static JdbcClient jdbc;
    static JdbcOperatorIdentityRepository repository;

    @BeforeAll
    static void migrate() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcOperatorIdentityRepository(jdbc);
    }

    @Test
    void acceptsInvitationOnceAndPersistsIdentityRolesAndTenantMembership() {
        var invite = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var email = "operator-" + invite + "@example.com";
        var invitationHash = fixtureHash("accepted invitation");
        var expiresAt = Instant.parse("2026-10-10T12:00:00Z");
        insertInvitation(invite, email, invitationHash, expiresAt);
        jdbc.sql("insert into operator_invitation_role (invitation_id, role) values (:invite, 'OPERATOR')")
                .param("invite", invite).update();
        jdbc.sql("insert into operator_invitation_tenant (invitation_id, tenant_id) values (:invite, :tenant)")
                .param("invite", invite).param("tenant", tenant).update();
        jdbc.sql("insert into operator_invitation_scope (invitation_id, scope) values (:invite, 'operator:read'), (:invite, 'leads:read')")
                .param("invite", invite).update();

        var accepted = repository.acceptInvitation(invitationHash, Instant.parse("2026-10-09T12:00:00Z"));
        var duplicate = repository.acceptInvitation(invitationHash, Instant.parse("2026-10-09T12:01:00Z"));

        assertThat(accepted).isPresent();
        assertThat(accepted.orElseThrow().email()).isEqualTo(email);
        assertThat(accepted.orElseThrow().roles()).containsExactly("OPERATOR");
        assertThat(accepted.orElseThrow().tenantIds()).containsExactly(tenant);
        assertThat(accepted.orElseThrow().scopes()).containsExactlyInAnyOrder("operator:read", "leads:read");
        assertThat(duplicate).isEmpty();
        assertThat(repository.findActiveByEmail(email)).contains(accepted.orElseThrow());
        assertThat(repository.findActiveById(accepted.orElseThrow().id())).contains(accepted.orElseThrow());
    }

    @Test
    void refusesExpiredInvitationWithoutCreatingAnIdentity() {
        var invite = UUID.randomUUID();
        var email = "expired-" + invite + "@example.com";
        var invitationHash = fixtureHash("expired invitation");
        insertInvitation(invite, email, invitationHash, Instant.parse("2026-10-09T11:00:00Z"));

        assertThat(repository.acceptInvitation(invitationHash, Instant.parse("2026-10-09T12:00:00Z"))).isEmpty();
        assertThat(jdbc.sql("select count(*) from operator_identity where email_normalized = :email")
                .param("email", email).query(Long.class).single()).isZero();
    }

    private static void insertInvitation(UUID id, String email, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        insert into operator_invitation (id, token_hash, email_normalized, display_name, expires_at)
                        values (:id, :tokenHash, :email, 'CRM Operator', :expiresAt)
                        """)
                .param("id", id)
                .param("tokenHash", tokenHash)
                .param("email", email)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    private static String fixtureHash(String value) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must be available in the Java runtime", exception);
        }
    }
}
