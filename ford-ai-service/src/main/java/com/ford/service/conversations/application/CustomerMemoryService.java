package com.ford.service.conversations.application;

import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository;
import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Pattern;

public class CustomerMemoryService implements SharedCustomerFactProvider {

    private static final Logger log = LoggerFactory.getLogger(CustomerMemoryService.class);
    private final CustomerMemoryProperties properties;
    private final CustomerIdentityFingerprint fingerprint;
    private final CustomerMemoryFactExtractor extractor;
    private final CustomerMemoryUpdatePolicy policy;
    private final CustomerMemoryIdentityRepository identities;
    private final CustomerMemoryFactRepository facts;

    public CustomerMemoryService(CustomerMemoryProperties properties, CustomerIdentityFingerprint fingerprint,
                                 CustomerMemoryFactExtractor extractor, CustomerMemoryUpdatePolicy policy,
                                 CustomerMemoryIdentityRepository identities, CustomerMemoryFactRepository facts) {
        this.properties = Objects.requireNonNull(properties);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.extractor = Objects.requireNonNull(extractor);
        this.policy = Objects.requireNonNull(policy);
        this.identities = Objects.requireNonNull(identities);
        this.facts = Objects.requireNonNull(facts);
    }

    public void captureExplicitFacts(UUID tenantId, UUID conversationId, ConversationMemoryKey key,
                                     UUID sourceReceiptId, Instant sourceOccurredAt, String customerMessage) {
        if (!properties.enabled() || key == null || key.channel() != com.ford.service.channels.domain.Channel.WHATSAPP
                || !tenantId.equals(key.tenantId()) || !conversationId.equals(key.conversationId())
                || sourceReceiptId == null || customerMessage == null || customerMessage.isBlank()) return;
        try {
            var identity = resolveIdentity(key);
            identities.linkConversation(tenantId, key.channel(), identity.id(), conversationId);
            var proposals = extractor.extract(customerMessage);
            if (!proposals.isEmpty() && facts.hasProcessedSource(identity.id(), sourceReceiptId)) return;
            for (var proposal : proposals) {
                policy.validate(customerMessage, proposal, identity, sourceReceiptId, conversationId,
                        sourceOccurredAt, identity.factsForgetBefore()).ifPresent(facts::upsert);
            }
        } catch (RuntimeException exception) {
            log.warn("Customer memory capture unavailable: tenantId={} conversationId={} exceptionType={}",
                    tenantId, conversationId, exception.getClass().getSimpleName());
        }
    }

    public List<CustomerShoppingFact> currentFacts(UUID tenantId, com.ford.service.channels.domain.Channel channel,
                                                   UUID conversationId, Instant now, int limit) {
        if (!properties.enabled() || channel != com.ford.service.channels.domain.Channel.WHATSAPP) return List.of();
        var identity = identities.findIdentityByConversation(tenantId, channel, conversationId);
        return identity == null ? List.of() : facts.findCurrent(identity.id(), now, limit);
    }

    public CustomerMemoryFactRepository.ForgetResult forget(UUID tenantId,
            com.ford.service.channels.domain.Channel channel, UUID conversationId, Instant at, UUID auditId,
            String actorId) {
        var identity = identities.findIdentityByConversation(tenantId, channel, conversationId);
        if (identity == null) return new CustomerMemoryFactRepository.ForgetResult(0, at);
        return facts.forget(identity.id(), at, auditId, actorId);
    }

    @Override
    public List<CustomerShoppingFact> relevantFacts(ConversationMemoryKey key, String currentMessage,
                                                    Instant now, int limit) {
        if (!properties.enabled() || key == null || key.channel() != com.ford.service.channels.domain.Channel.WHATSAPP
                || currentMessage == null || currentMessage.isBlank() || limit < 1) return List.of();
        try {
            var identity = resolveIdentity(key);
            if (!key.tenantId().equals(identity.tenantId()) || key.channel() != identity.channel()) return List.of();
            identities.linkConversation(key.tenantId(), key.channel(), identity.id(), key.conversationId());
            var candidates = facts.findCurrent(identity.id(), now, Math.min(limit, 50));
            var normalized = currentMessage.toLowerCase(Locale.ROOT);
            var shoppingQuestion = Pattern.compile("(?iu)\\b(auto|veh[ií]culo|comprar|compra|presupuesto|precio|financiamiento|mensualidad|plazo|cu[aá]ndo|cuando)\\b")
                    .matcher(currentMessage).find();
            var selected = new java.util.ArrayList<CustomerShoppingFact>();
            var chars = 0;
            for (var fact : candidates) {
                if (!isRelevant(fact, normalized, shoppingQuestion)) continue;
                var cost = fact.type().name().length() + fact.subjectKey().length() + fact.value().length() + 48;
                if (selected.size() >= Math.min(3, limit) || chars + cost > 600) break;
                selected.add(fact);
                chars += cost;
            }
            return List.copyOf(selected);
        } catch (RuntimeException exception) {
            log.warn("Customer memory context unavailable: exceptionType={}", exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private static boolean isRelevant(CustomerShoppingFact fact, String normalizedMessage, boolean shoppingQuestion) {
        return switch (fact.type()) {
            case VEHICLE_MODEL, VEHICLE_TRIM -> normalizedMessage.contains(fact.subjectKey().toLowerCase(Locale.ROOT))
                    || normalizedMessage.contains(fact.value().toLowerCase(Locale.ROOT).replace("ford ", ""));
            case PROMOTION_DECLINE -> normalizedMessage.contains(fact.subjectKey().toLowerCase(Locale.ROOT))
                    || normalizedMessage.contains(fact.value().toLowerCase(Locale.ROOT));
            case SHOPPING_BUDGET, PURCHASE_TIMEFRAME -> shoppingQuestion;
        };
    }

    public CustomerMemoryIdentity resolveIdentity(ConversationMemoryKey key) {
        if (!properties.enabled()) throw new IllegalStateException("customer memory is disabled");
        properties.validate();
        var candidates = new java.util.ArrayList<CustomerMemoryIdentityRepository.VersionedFingerprint>();
        candidates.add(new CustomerMemoryIdentityRepository.VersionedFingerprint(properties.hmacKeyVersion(),
                fingerprint.fingerprint(key.tenantId(), key.channel(), key.participantId(),
                        properties.hmacKeyVersion(), properties.hmacKeyBytes())));
        if (properties.previousHmacKey() != null && !properties.previousHmacKey().isBlank()) {
            candidates.add(new CustomerMemoryIdentityRepository.VersionedFingerprint(properties.previousHmacKeyVersion(),
                    fingerprint.fingerprint(key.tenantId(), key.channel(), key.participantId(),
                            properties.previousHmacKeyVersion(), properties.previousHmacKeyBytes())));
        }
        return identities.resolveOrCreateIdentity(key.tenantId(), key.channel(), candidates);
    }
}
