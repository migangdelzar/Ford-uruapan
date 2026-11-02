package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.CustomerMemoryFactExtractor;
import com.ford.service.conversations.application.CustomerMemoryService;
import com.ford.service.conversations.application.CustomerMemoryUpdatePolicy;
import com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository;
import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryServiceTest {

    @Test
    void capturesOnlyCurrentMessageFactsAndIsIdempotentByReceipt() {
        var identity = new CustomerMemoryIdentity(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                null, Instant.now());
        var identityRepository = new FakeIdentityRepository(identity);
        var factRepository = new FakeFactRepository();
        var service = new CustomerMemoryService(testProperties(), new com.ford.service.conversations.api.CustomerIdentityFingerprint(),
                new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identityRepository, factRepository);
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(identity.tenantId(),
                Channel.WHATSAPP, "provider-account-1", "523331733925", conversation);

        var receivedAt = Instant.now();
        service.captureExplicitFacts(identity.tenantId(), conversation, key, receipt, receivedAt,
                "Me interesa la Ford Bronco y mi presupuesto es $850,000.");
        service.captureExplicitFacts(identity.tenantId(), conversation, key, receipt, receivedAt,
                "Me interesa la Ford Bronco y mi presupuesto es $850,000.");

        assertThat(factRepository.facts).hasSize(2);
        assertThat(identityRepository.linkedConversations).containsExactly(conversation, conversation);
        assertThat(factRepository.facts).extracting(CustomerShoppingFact::type)
                .contains(CustomerShoppingFactType.VEHICLE_MODEL, CustomerShoppingFactType.SHOPPING_BUDGET);
    }

    @Test
    void sharesOnlyRelevantFactsAcrossProviderAccountsAndFailsClosedForOtherTenant() {
        var tenant = UUID.randomUUID();
        var identity = new CustomerMemoryIdentity(UUID.randomUUID(), tenant, Channel.WHATSAPP, null, Instant.now());
        var identities = new FakeIdentityRepository(identity);
        var facts = new FakeFactRepository();
        var now = Instant.now();
        facts.facts.add(fact(identity.id(), CustomerShoppingFactType.VEHICLE_MODEL, "bronco", "Ford Bronco",
                com.ford.service.conversations.domain.CustomerShoppingFactState.ACTIVE, now, now.plusSeconds(86400)));
        facts.facts.add(fact(identity.id(), CustomerShoppingFactType.SHOPPING_BUDGET, "budget", "850000",
                com.ford.service.conversations.domain.CustomerShoppingFactState.ACTIVE, now, now.plusSeconds(86400)));
        var service = new CustomerMemoryService(testProperties(), new com.ford.service.conversations.api.CustomerIdentityFingerprint(),
                new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identities, facts);
        var firstConversation = UUID.randomUUID();
        var secondConversation = UUID.randomUUID();
        var first = new com.ford.service.conversations.domain.ConversationMemoryKey(tenant, Channel.WHATSAPP,
                "meta-account-old", "523331733925", firstConversation);
        var second = new com.ford.service.conversations.domain.ConversationMemoryKey(tenant, Channel.WHATSAPP,
                "meta-account-new", "523331733925", secondConversation);

        var oldThreadFacts = service.relevantFacts(first, "¿Qué versiones de Bronco hay?", now, 10);
        var newThreadFacts = service.relevantFacts(second, "¿Qué versiones de Bronco hay?", now, 10);
        var otherTenant = new com.ford.service.conversations.domain.ConversationMemoryKey(UUID.randomUUID(),
                Channel.WHATSAPP, "meta-account-new", "523331733925", UUID.randomUUID());

        assertThat(oldThreadFacts).extracting(CustomerShoppingFact::subjectKey).containsExactly("bronco");
        assertThat(newThreadFacts).extracting(CustomerShoppingFact::subjectKey).containsExactly("bronco");
        assertThat(service.relevantFacts(otherTenant, "¿Qué versiones de Bronco hay?", now, 10)).isEmpty();
        assertThat(identities.linkedConversations).contains(firstConversation, secondConversation);
    }

    @Test
    void capsRelevantFactsAndIncludesBudgetOnlyForShoppingQuestions() {
        var identity = new CustomerMemoryIdentity(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                null, Instant.now());
        var identities = new FakeIdentityRepository(identity);
        var facts = new FakeFactRepository();
        var now = Instant.now();
        facts.facts.add(fact(identity.id(), CustomerShoppingFactType.SHOPPING_BUDGET, "budget", "850000",
                com.ford.service.conversations.domain.CustomerShoppingFactState.ACTIVE, now, now.plusSeconds(86400)));
        facts.facts.add(fact(identity.id(), CustomerShoppingFactType.PURCHASE_TIMEFRAME, "purchase-timeframe", "3 meses",
                com.ford.service.conversations.domain.CustomerShoppingFactState.ACTIVE, now, now.plusSeconds(86400)));
        var service = new CustomerMemoryService(testProperties(), new com.ford.service.conversations.api.CustomerIdentityFingerprint(),
                new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identities, facts);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(identity.tenantId(), Channel.WHATSAPP,
                "meta-account", "523331733925", UUID.randomUUID());

        assertThat(service.relevantFacts(key, "Hola", now, 50)).isEmpty();
        assertThat(service.relevantFacts(key, "Busco un auto y quiero comprar en tres meses", now, 50))
                .hasSizeLessThanOrEqualTo(3);
    }

    private static CustomerShoppingFact fact(UUID identityId, CustomerShoppingFactType type, String subject,
            String value, com.ford.service.conversations.domain.CustomerShoppingFactState state,
            Instant receivedAt, Instant expiry) {
        return new CustomerShoppingFact(identityId, type, subject, value, state, UUID.randomUUID(), UUID.randomUUID(),
                receivedAt, 1, receivedAt, receivedAt, expiry, 0);
    }

    private static com.ford.service.conversations.application.CustomerMemoryProperties testProperties() {
        var properties = new com.ford.service.conversations.application.CustomerMemoryProperties();
        properties.setEnabled(true);
        properties.setHmacKey("01234567890123456789012345678901");
        properties.setHmacKeyVersion(1);
        return properties;
    }

    private static final class FakeIdentityRepository implements CustomerMemoryIdentityRepository {
        private final CustomerMemoryIdentity identity;
        private final List<UUID> linkedConversations = new ArrayList<>();

        private FakeIdentityRepository(CustomerMemoryIdentity identity) { this.identity = identity; }
        @Override public CustomerMemoryIdentity resolveOrCreateIdentity(UUID tenantId, Channel channel,
                List<VersionedFingerprint> fingerprints) { return identity; }
        @Override public void linkConversation(UUID tenantId, Channel channel, UUID identityId, UUID conversationId) {
            linkedConversations.add(conversationId);
        }
        @Override public List<ConversationIdentitySource> nextConversationBatch(UUID afterConversationId, int limit) {
            return List.of();
        }
        @Override public CustomerMemoryIdentity findIdentityByConversation(UUID tenantId, Channel channel, UUID conversationId) {
            return identity;
        }
    }

    private static final class FakeFactRepository implements CustomerMemoryFactRepository {
        private final List<CustomerShoppingFact> facts = new ArrayList<>();
        @Override public FactUpdateResult upsert(CustomerShoppingFact fact) {
            if (facts.stream().anyMatch(existing -> existing.sourceReceiptId().equals(fact.sourceReceiptId())
                    && existing.type() == fact.type())) return new FactUpdateResult(false, 0);
            facts.add(fact); return new FactUpdateResult(true, 0);
        }
        @Override public boolean hasProcessedSource(UUID identityId, UUID sourceReceiptId) {
            return facts.stream().anyMatch(existing -> existing.sourceReceiptId().equals(sourceReceiptId));
        }
        @Override public List<CustomerShoppingFact> findCurrent(UUID identityId, Instant now, int limit) { return List.copyOf(facts); }
        @Override public ForgetResult forget(UUID identityId, Instant forgottenAt, UUID auditId, String actorId) { return new ForgetResult(0, forgottenAt); }
        @Override public void reopen(UUID identityId, CustomerShoppingFactType type, String subjectKey,
                                     UUID sourceReceiptId, Instant reopenedAt) { }
    }
}
