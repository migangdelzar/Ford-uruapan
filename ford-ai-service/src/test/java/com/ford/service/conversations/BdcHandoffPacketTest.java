package com.ford.service.conversations;

import com.ford.service.conversations.api.BdcHandoffPacket;
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
    void completePacketCountsAllFiveFields() {
        var packet = new BdcHandoffPacket(
                Optional.of("Ranger"), Optional.of("financiamiento"), Optional.of("20 mil pesos"),
                Optional.of("este mes"), Optional.of("María Elena"));

        assertThat(packet.completeness()).isEqualTo(5);
        assertThat(packet.partial()).isFalse();
        assertThat(packet.arguments()).containsKeys(
                "vehicleModel", "paymentMethod", "downPayment", "purchaseTimeframe", "customerName");
    }

    @Test
    void partialPacketCountsOnlyPresentFields() {
        var packet = new BdcHandoffPacket(
                Optional.of("Ranger"), Optional.empty(), Optional.empty(),
                Optional.of("este mes"), Optional.empty());

        assertThat(packet.completeness()).isEqualTo(2);
        assertThat(packet.partial()).isTrue();
        assertThat(packet.arguments()).containsOnlyKeys("vehicleModel", "purchaseTimeframe");
    }

    @Test
    void rejectsNullOptionalsAndUnsupportedValues() {
        assertThatThrownBy(() -> new BdcHandoffPacket(
                null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BdcHandoffPacket(
                Optional.of("\u0000"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
