package com.ford.service.conversations;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BdcHandoffPacketTest {

    @Test
    void emptyPacketIsPartialWithZeroCompleteness() {
        var packet = BdcHandoffPacket.empty();

        assertThat(packet.completeness()).isZero();
        assertThat(packet.partial()).isTrue();
        assertThat(packet.arguments()).isEmpty();
    }

    @Test
    void confirmedShoppingSummaryOmitsLegacyFinanceAndBoundsCompletenessAtFive() {
        var packet = new BdcHandoffPacket(Optional.of("Ranger"), Optional.of("XLT"),
                Optional.of("para viajar con mi familia"), Optional.of("este mes"), Optional.empty(),
                Optional.of("sí"), Optional.empty(), Optional.of("María Elena"));

        assertThat(packet.completeness()).isEqualTo(5);
        assertThat(packet.partial()).isFalse();
        assertThat(packet.arguments()).containsKeys("vehicleModel", "vehicleTrim", "needs", "purchaseTimeframe",
                "tradeInInterest", "customerName").doesNotContainKeys("paymentMethod", "downPayment");
    }

    @Test
    void partialPacketCountsOnlyPresentFields() {
        var packet = new BdcHandoffPacket(Optional.of("Ranger"), Optional.empty(), Optional.empty(),
                Optional.of("este mes"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(packet.completeness()).isEqualTo(2);
        assertThat(packet.partial()).isTrue();
        assertThat(packet.arguments()).containsOnlyKeys("vehicleModel", "purchaseTimeframe");
    }

    @Test
    void rejectsNullOptionalsAndUnsupportedValues() {
        assertThatThrownBy(() -> new BdcHandoffPacket(null, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BdcHandoffPacket(Optional.of("\u0000"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyFinanceKeysAreIgnoredAndContactTextIsRedactedFromShoppingFields() {
        var packet = BdcHandoffPacket.fromArguments(java.util.Map.of(
                "paymentMethod", "financiamiento", "downPayment", "20 mil pesos",
                "needs", "llamar al 452 123 4567 o ana@example.com o 452.123.4567",
                "buyerPriceRange", "500 mil pesos"));

        assertThat(packet.arguments()).containsOnlyKeys("needs", "buyerPriceRange")
                .containsEntry("needs", "llamar al [dato de contacto omitido] o [dato de contacto omitido] o [dato de contacto omitido]")
                .doesNotContainKeys("paymentMethod", "downPayment");
        assertThat(packet.toString()).doesNotContain("452 123 4567", "ana@example.com");
    }

    @Test
    void customerNameDoesNotCountAsAConfirmedShoppingCompletenessField() {
        var packet = new BdcHandoffPacket(Optional.of("Ranger"), Optional.of("XLT"),
                Optional.of("para viajar con mi familia"), Optional.of("este mes"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("Ana López"));

        assertThat(packet.completeness()).isEqualTo(4);
        assertThat(packet.partial()).isTrue();
    }

    @Test
    void alternateContactRoundTripsWithConsentButDoesNotChangeCompletenessOrAppearInToString() {
        var contact = new AlternateContactValue(ContactType.EMAIL, "Ana.Rios@example.com",
                java.time.Instant.parse("2026-10-23T19:00:00Z"), "receipt-123", "alternate-contact-consent-v1");
        var packet = BdcHandoffPacket.empty().withAlternateContact(contact);

        assertThat(packet.completeness()).isZero();
        assertThat(BdcHandoffPacket.fromArguments(packet.arguments())).isEqualTo(packet);
        assertThat(packet.toString()).doesNotContain("Ana.Rios@example.com");
        assertThat(packet.arguments()).containsEntry("alternateContactValue", "Ana.Rios@example.com")
                .containsEntry("alternateContactConsentedAt", "2026-10-23T19:00:00Z");
    }
}
