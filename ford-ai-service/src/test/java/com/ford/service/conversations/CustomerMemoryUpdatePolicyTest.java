package com.ford.service.conversations;

import com.ford.service.conversations.application.CustomerMemoryFactExtractor;
import com.ford.service.conversations.application.CustomerMemoryUpdatePolicy;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerMemoryUpdatePolicyTest {

    private final CustomerMemoryFactExtractor extractor = new CustomerMemoryFactExtractor();
    private final CustomerMemoryUpdatePolicy policy = new CustomerMemoryUpdatePolicy();

    @Test
    void extractsExplicitModelBudgetAndTimeframeFromCurrentCustomerMessage() {
        var proposals = extractor.extract("Me interesa la Ford Bronco, mi presupuesto es de $850,000 y compro en 3 meses.");

        assertThat(proposals).extracting(CustomerMemoryFactExtractor.CustomerMemoryFactProposal::type)
                .contains(CustomerShoppingFactType.VEHICLE_MODEL,
                        CustomerShoppingFactType.SHOPPING_BUDGET,
                        CustomerShoppingFactType.PURCHASE_TIMEFRAME);
        assertThat(proposals).allSatisfy(proposal ->
                assertThat(policy.validate("Me interesa la Ford Bronco, mi presupuesto es de $850,000 y compro en 3 meses.",
                        proposal, identity(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null)).isPresent());
    }

    @Test
    void rejectsAssistantTextSensitiveContentAndNonEvidenceProposals() {
        assertThat(extractor.extract("El asesor dijo: te interesa la Ford Bronco.")).isEmpty();
        assertThat(extractor.extract("Mi RFC es ABCD123456 y mi presupuesto es $50,000.")).isEmpty();
        var proposal = new CustomerMemoryFactExtractor.CustomerMemoryFactProposal(
                CustomerShoppingFactType.VEHICLE_MODEL, "ford-bronco", "Ford Bronco",
                "quiero un Explorer", CustomerShoppingFactState.ACTIVE);
        assertThat(policy.validate("Quiero una Ford Bronco", proposal,
                identity(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null)).isEmpty();
    }

    @Test
    void scopesModelAndOfferDeclinesToExactSubject() {
        var proposals = extractor.extract("No me interesa la Ford Escape ni la promoción oferta-123.");

        assertThat(proposals).allSatisfy(proposal ->
            assertThat(policy.validate("No me interesa la Ford Escape ni la promoción oferta-123.", proposal,
                    identity(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null))
                        .isPresent());
        assertThat(proposals).anySatisfy(proposal -> {
            assertThat(proposal.type()).isEqualTo(CustomerShoppingFactType.VEHICLE_MODEL);
            assertThat(proposal.state()).isEqualTo(CustomerShoppingFactState.DECLINED);
            assertThat(proposal.subjectKey()).contains("escape");
        });
        assertThat(proposals).anySatisfy(proposal -> {
            assertThat(proposal.type()).isEqualTo(CustomerShoppingFactType.PROMOTION_DECLINE);
            assertThat(proposal.state()).isEqualTo(CustomerShoppingFactState.DECLINED);
            assertThat(proposal.subjectKey()).isEqualTo("oferta-123");
        });
    }

    @Test
    void rejectsSourceMessagesAtOrBeforeForgetBoundary() {
        var now = Instant.now();
        var proposal = extractor.extract("Me interesa la Ford Bronco").getFirst();
        assertThat(policy.validate("Me interesa la Ford Bronco", proposal, identity(), UUID.randomUUID(),
                UUID.randomUUID(), now, now)).isEmpty();
        assertThat(policy.validate("Me interesa la Ford Bronco", proposal, identity(), UUID.randomUUID(),
                UUID.randomUUID(), now.plusSeconds(1), now)).isPresent();
    }

    @Test
    void acceptedActiveFactsExpireAfterNinetyDaysAndDeclinesDoNotExpire() {
        var now = Instant.parse("2026-10-20T12:00:00Z");
        var activeProposal = extractor.extract("Me interesa la Ford Bronco").getFirst();
        var active = policy.validate("Me interesa la Ford Bronco", activeProposal,
                identity(), UUID.randomUUID(), UUID.randomUUID(), now, null).orElseThrow();
        assertThat(active.expiresAt()).isEqualTo(now.plus(java.time.Duration.ofDays(90)));

        var declinedProposal = extractor.extract("No me interesa la Ford Escape").stream()
                .filter(proposal -> proposal.type() == CustomerShoppingFactType.VEHICLE_MODEL).findFirst().orElseThrow();
        var declined = policy.validate("No me interesa la Ford Escape", declinedProposal,
                identity(), UUID.randomUUID(), UUID.randomUUID(), now, null).orElseThrow();
        assertThat(declined.expiresAt()).isNull();
    }

    @Test
    void factRejectsRawTextAndInvalidBounds() {
        assertThatThrownBy(() -> new com.ford.service.conversations.domain.CustomerShoppingFact(
                UUID.randomUUID(), CustomerShoppingFactType.VEHICLE_MODEL, "model", "x".repeat(161),
                CustomerShoppingFactState.ACTIVE, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 1,
                Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelReopenRequiresExplicitPositiveInterestAndTargetsExactModel() {
        var reopened = extractor.extract("Ahora sí me interesa la Ford Escape");
        assertThat(reopened).singleElement().satisfies(proposal -> {
            assertThat(proposal.type()).isEqualTo(CustomerShoppingFactType.VEHICLE_MODEL);
            assertThat(proposal.subjectKey()).isEqualTo("escape");
            assertThat(proposal.state()).isEqualTo(CustomerShoppingFactState.ACTIVE);
        });
    }

    @Test
    void bareModelMentionIsNotAnExplicitPreference() {
        assertThat(extractor.extract("Vi una Ford Bronco en la calle.")).isEmpty();
    }

    @Test
    void rejectsFinanceAndSensitiveContentWithoutCreatingAnyShoppingFact() {
        assertThat(extractor.extract("No quiero comprar en 3 meses; mi cuenta bancaria termina en 1234.")).isEmpty();
        assertThat(extractor.extract("Mi score es 720 y gano $50,000, pero busco una Ford Bronco." )).isEmpty();
        assertThat(extractor.extract("No me interesa el presupuesto de $800,000 ni comprar en 2 meses."))
                .isEmpty();
    }

    @Test
    void rejectsAccountNumberAndCreditIdentifiersEvenWhenBudgetIsAlsoMentioned() {
        assertThat(extractor.extract("Mi número de cuenta es 1234567890 y mi presupuesto es $850,000."))
                .isEmpty();
        assertThat(extractor.extract("Mi CURP es ABCD123456 y quiero comprar una Ford Bronco."))
                .isEmpty();
    }

    @Test
    void rejectsCreditScoreAndIncomeTextRatherThanStoringItAsShoppingContext() {
        assertThat(extractor.extract("Mi score es 720, gano $50,000 y mi presupuesto para auto es $850,000."))
                .isEmpty();
        assertThat(extractor.extract("Mi salario es de $50,000 y quiero una Ford Escape." )).isEmpty();
    }

    @Test
    void repeatedSourceReceiptCannotBeAppliedTwice() {
        var now = Instant.now();
        var identity = identity();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var proposal = extractor.extract("Me interesa la Ford Bronco").getFirst();
        var first = policy.validate("Me interesa la Ford Bronco", proposal, identity, receipt,
                conversation, now, null).orElseThrow();
        var replay = policy.validate("Me interesa la Ford Bronco", proposal, identity, receipt,
                conversation, now, null).orElseThrow();
        assertThat(first.sourceReceiptId()).isEqualTo(replay.sourceReceiptId());
        assertThat(first).isEqualTo(replay);
    }

    private static com.ford.service.conversations.domain.CustomerMemoryIdentity identity() {
        return new com.ford.service.conversations.domain.CustomerMemoryIdentity(UUID.randomUUID(), UUID.randomUUID(),
                com.ford.service.channels.domain.Channel.WHATSAPP, null, Instant.now());
    }
}
