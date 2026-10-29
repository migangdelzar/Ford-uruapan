package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorIdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    void normalizesEmailBeforeLookingUpActiveIdentity() {
        var identity = identity("operator@example.com", true);
        var repository = new FakeOperatorIdentityRepository(identity);
        var service = new OperatorIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var found = service.findActiveByEmail("  Operator@Example.com ");

        assertThat(found).contains(identity);
        assertThat(repository.lookedUpEmail).isEqualTo("operator@example.com");
    }

    @Test
    void doesNotReturnDisabledIdentityEvenIfRepositoryReturnsIt() {
        var repository = new FakeOperatorIdentityRepository(identity("operator@example.com", false));
        var service = new OperatorIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.findActiveByEmail("operator@example.com")).isEmpty();
    }

    @Test
    void returnsAnActiveIdentityByStableId() {
        var identity = identity("operator@example.com", true);
        var repository = new FakeOperatorIdentityRepository(identity);
        var service = new OperatorIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.findActiveById(identity.id())).contains(identity);
        assertThat(repository.lookedUpId).isEqualTo(identity.id());
    }

    @Test
    void passesOnlySha256TokenHashAndCurrentTimeToAtomicInvitationAcceptance() {
        var token = "invite-secret-value";
        var identity = identity("operator@example.com", true);
        var repository = new FakeOperatorIdentityRepository(identity);
        var service = new OperatorIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.acceptInvite(token)).contains(identity);

        assertThat(repository.acceptedTokenHash)
                .isEqualTo("0d1a6480418a18cc319d444ae8dee8d86c576ea7812996eef16cbcb90f688952")
                .doesNotContain(token);
        assertThat(repository.acceptedAt).isEqualTo(NOW);
    }

    @Test
    void rejectsBlankInviteWithoutQueryingRepository() {
        var repository = new FakeOperatorIdentityRepository(null);
        var service = new OperatorIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.acceptInvite("  ")).isEmpty();
        assertThat(repository.acceptedTokenHash).isNull();
    }

    @Test
    void mapsOnlyKnownCrmRolesToBackendAuthorities() {
        assertThat(OperatorIdentity.roleFromCrmRole("asesor")).isEqualTo("OPERATOR");
        assertThat(OperatorIdentity.roleFromCrmRole("gerente")).isEqualTo("MANAGER");
        assertThat(OperatorIdentity.roleFromCrmRole("propietario")).isEqualTo("ADMIN");
        assertThatThrownBy(() -> OperatorIdentity.roleFromCrmRole("owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsScopesOutsideThePublishedApiContract() {
        assertThatThrownBy(() -> new OperatorIdentity(
                UUID.randomUUID(), "operator@example.com", "CRM Operator", true,
                Set.of("OPERATOR"), Set.of(UUID.randomUUID()), Set.of("admin:*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operator must have only supported API scopes");
    }

    private static OperatorIdentity identity(String email, boolean enabled) {
        return new OperatorIdentity(
                UUID.fromString("0b3067af-6374-4c0a-80d0-5218c968ff47"),
                email,
                "CRM Operator",
                enabled,
                Set.of("OPERATOR"),
                Set.of(UUID.fromString("678135f8-74bb-4ed7-8e66-40ff4280f5db")),
                Set.of("operator:read", "operator:write"));
    }

    private static final class FakeOperatorIdentityRepository implements OperatorIdentityRepository {
        private final OperatorIdentity identity;
        private String lookedUpEmail;
        private UUID lookedUpId;
        private String acceptedTokenHash;
        private Instant acceptedAt;

        private FakeOperatorIdentityRepository(OperatorIdentity identity) {
            this.identity = identity;
        }

        @Override
        public Optional<OperatorIdentity> findActiveByEmail(String normalizedEmail) {
            lookedUpEmail = normalizedEmail;
            return Optional.ofNullable(identity).filter(OperatorIdentity::enabled);
        }

        @Override
        public Optional<OperatorIdentity> findActiveById(UUID id) {
            lookedUpId = id;
            return Optional.ofNullable(identity).filter(OperatorIdentity::enabled)
                    .filter(candidate -> candidate.id().equals(id));
        }

        @Override
        public Optional<OperatorIdentity> acceptInvitation(String tokenHash, Instant acceptedAt) {
            this.acceptedTokenHash = tokenHash;
            this.acceptedAt = acceptedAt;
            return Optional.ofNullable(identity);
        }
    }
}
