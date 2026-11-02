package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class CustomerMemoryUpdatePolicy {

    private static final Pattern SENSITIVE = Pattern.compile("(?iu)\\b(?:rfc|curp|n[uú]mero de cuenta|cuenta bancaria|cuenta|score|puntaje|ingreso|gano|sueldo|salario)\\b");
    private static final Pattern SAFE_VALUE = Pattern.compile("[\\p{L}\\p{N} $.,-]{1,160}");

    public Optional<CustomerShoppingFact> validate(String customerMessage,
                                                  CustomerMemoryFactExtractor.CustomerMemoryFactProposal proposal) {
        return validateEvidence(customerMessage, proposal, null, null, null, Instant.now(), null);
    }

    public Optional<CustomerShoppingFact> validate(String customerMessage,
                                                  CustomerMemoryFactExtractor.CustomerMemoryFactProposal proposal,
                                                  CustomerMemoryIdentity identity,
                                                  java.util.UUID sourceReceiptId,
                                                  java.util.UUID sourceConversationId,
                                                  Instant sourceOccurredAt, Instant factsForgetBefore) {
        if (sourceReceiptId == null || sourceConversationId == null) return Optional.empty();
        return validateEvidence(customerMessage, proposal, identity, sourceReceiptId, sourceConversationId,
                sourceOccurredAt, factsForgetBefore);
    }

    private Optional<CustomerShoppingFact> validateEvidence(String customerMessage,
                                                            CustomerMemoryFactExtractor.CustomerMemoryFactProposal proposal,
                                                            CustomerMemoryIdentity identity,
                                                            java.util.UUID sourceReceiptId,
                                                            java.util.UUID sourceConversationId,
                                                            Instant sourceOccurredAt,
                                                            Instant factsForgetBefore) {
        if (customerMessage == null || proposal == null || proposal.type() == null || proposal.state() == null
                || proposal.exactUserEvidence() == null || proposal.exactUserEvidence().isBlank()
                || !customerMessage.contains(proposal.exactUserEvidence())
                || proposal.subjectKey() == null || proposal.value() == null
                || proposal.subjectKey().isBlank() || proposal.subjectKey().length() > 120
                || proposal.value().isBlank() || proposal.value().length() > 160
                || !SAFE_VALUE.matcher(proposal.value()).matches()
                || SENSITIVE.matcher(customerMessage).find()
                || SENSITIVE.matcher(proposal.value()).find()
                || sourceOccurredAt == null
                || factsForgetBefore != null && !sourceOccurredAt.isAfter(factsForgetBefore)) {
            return Optional.empty();
        }
        if (proposal.state() == CustomerShoppingFactState.DECLINED
                && proposal.type() != CustomerShoppingFactType.VEHICLE_MODEL
                && proposal.type() != CustomerShoppingFactType.VEHICLE_TRIM
                && proposal.type() != CustomerShoppingFactType.PROMOTION_DECLINE) {
            return Optional.empty();
        }
        var value = proposal.value().trim();
            var expiresAt = proposal.state() == CustomerShoppingFactState.DECLINED
                    ? null : sourceOccurredAt.plus(90, ChronoUnit.DAYS);
        try {
            var identityId = identity == null ? new java.util.UUID(0L, 0L) : identity.id();
            return Optional.of(new CustomerShoppingFact(identityId, proposal.type(),
                    proposal.subjectKey().toLowerCase(Locale.ROOT), value, proposal.state(), sourceConversationId,
                    sourceReceiptId, sourceOccurredAt, 1, sourceOccurredAt, sourceOccurredAt, expiresAt, 0));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
