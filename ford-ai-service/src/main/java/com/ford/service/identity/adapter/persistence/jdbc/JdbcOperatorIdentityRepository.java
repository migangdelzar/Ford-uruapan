package com.ford.service.identity.adapter.persistence.jdbc;

import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.application.OperatorIdentityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JdbcOperatorIdentityRepository implements OperatorIdentityRepository {

    private final JdbcClient jdbc;

    public JdbcOperatorIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OperatorIdentity> findActiveByEmail(String normalizedEmail) {
        return jdbc.sql("select id from operator_identity where email_normalized = :email and enabled = true")
                .param("email", normalizedEmail)
                .query(UUID.class)
                .optional()
                .flatMap(this::findById);
    }

    @Override
    public Optional<OperatorIdentity> findActiveById(UUID id) {
        return jdbc.sql("select id from operator_identity where id = :id and enabled = true")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .flatMap(this::findById);
    }

    @Override
    @Transactional
    public Optional<OperatorIdentity> acceptInvitation(String tokenHash, Instant acceptedAt) {
        var invitation = jdbc.sql("""
                        select id, email_normalized, display_name
                        from operator_invitation
                        where token_hash = :tokenHash
                          and accepted_at is null
                          and expires_at > :acceptedAt
                        for update
                        """)
                .param("tokenHash", tokenHash)
                .param("acceptedAt", Timestamp.from(acceptedAt))
                .query((rs, row) -> new Invitation(
                        rs.getObject("id", UUID.class),
                        rs.getString("email_normalized"),
                        rs.getString("display_name")))
                .optional();
        if (invitation.isEmpty()) return Optional.empty();

        var source = invitation.orElseThrow();
        var roles = jdbc.sql("select role from operator_invitation_role where invitation_id = :id order by role")
                .param("id", source.id())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        var tenantIds = jdbc.sql("select tenant_id from operator_invitation_tenant where invitation_id = :id order by tenant_id")
                .param("id", source.id())
                .query(UUID.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        var scopes = jdbc.sql("select scope from operator_invitation_scope where invitation_id = :id order by scope")
                .param("id", source.id())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        if (roles.isEmpty() || tenantIds.isEmpty() || scopes.isEmpty()) return Optional.empty();

        var identity = new OperatorIdentity(
                UUID.randomUUID(), source.email(), source.displayName(), true, roles, tenantIds, scopes);
        var consumed = jdbc.sql("""
                        update operator_invitation
                        set accepted_at = :acceptedAt
                        where id = :id and accepted_at is null and expires_at > :acceptedAt
                        """)
                .param("acceptedAt", Timestamp.from(acceptedAt))
                .param("id", source.id())
                .update();
        if (consumed != 1) return Optional.empty();

        jdbc.sql("insert into operator_identity (id, email_normalized, display_name, enabled) values (:id, :email, :name, true)")
                .param("id", identity.id())
                .param("email", identity.email())
                .param("name", identity.displayName())
                .update();
        for (var role : identity.roles()) {
            jdbc.sql("insert into operator_identity_role (operator_id, role) values (:id, :role)")
                    .param("id", identity.id())
                    .param("role", role)
                    .update();
        }
        for (var tenantId : identity.tenantIds()) {
            jdbc.sql("insert into operator_identity_tenant (operator_id, tenant_id) values (:id, :tenant)")
                    .param("id", identity.id())
                    .param("tenant", tenantId)
                    .update();
        }
        for (var scope : identity.scopes()) {
            jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                    .param("id", identity.id())
                    .param("scope", scope)
                    .update();
        }
        return Optional.of(identity);
    }

    private Optional<OperatorIdentity> findById(UUID id) {
        var base = jdbc.sql("""
                        select id, email_normalized, display_name, enabled
                        from operator_identity where id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> new IdentityBase(
                        rs.getObject("id", UUID.class),
                        rs.getString("email_normalized"),
                        rs.getString("display_name"),
                        rs.getBoolean("enabled")))
                .optional();
        if (base.isEmpty()) return Optional.empty();

        var identity = base.orElseThrow();
        var roles = jdbc.sql("select role from operator_identity_role where operator_id = :id order by role")
                .param("id", identity.id())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        var tenantIds = jdbc.sql("select tenant_id from operator_identity_tenant where operator_id = :id order by tenant_id")
                .param("id", identity.id())
                .query(UUID.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        var scopes = jdbc.sql("select scope from operator_identity_scope where operator_id = :id order by scope")
                .param("id", identity.id())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        return Optional.of(new OperatorIdentity(
                identity.id(), identity.email(), identity.displayName(), identity.enabled(), roles, tenantIds, scopes));
    }

    private record Invitation(UUID id, String email, String displayName) { }

    private record IdentityBase(UUID id, String email, String displayName, boolean enabled) { }
}
