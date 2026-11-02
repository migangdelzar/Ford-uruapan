package com.ford.service.handoffcontact.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import com.ford.service.handoffcontact.api.EncryptedContactEnvelope;
import com.ford.service.handoffcontact.api.StagingContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlternateContactStagingCipherTest {
    private static final byte[] KEY = new byte[32];

    @Test
    void encryptsAndDecryptsWithTheSameTenantConversationAndReceiptAad() {
        var cipher = new AlternateContactStagingCipher(properties(1, Base64.getEncoder().encodeToString(KEY)),
                new ObjectMapper().findAndRegisterModules());
        var context = context();
        var values = List.of(contact());

        var envelope = cipher.encrypt(values, context);

        assertThat(envelope.ciphertext()).isNotEmpty();
        assertThat(envelope.nonce()).hasSize(12);
        assertThat(cipher.decrypt(envelope, context)).containsExactlyElementsOf(values);
    }

    @Test
    void rejectsCiphertextBoundToDifferentTenantContext() {
        var cipher = new AlternateContactStagingCipher(properties(1, Base64.getEncoder().encodeToString(KEY)),
                new ObjectMapper().findAndRegisterModules());
        var envelope = cipher.encrypt(List.of(contact()), context());

        assertThatThrownBy(() -> cipher.decrypt(envelope,
                new StagingContext(UUID.randomUUID(), context().conversationId(), context().receiptId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsModifiedNonceOrCiphertext() {
        var cipher = new AlternateContactStagingCipher(properties(1, Base64.getEncoder().encodeToString(KEY)),
                new ObjectMapper().findAndRegisterModules());
        var context = context();
        var envelope = cipher.encrypt(List.of(contact()), context);
        var modified = envelope.ciphertext();
        modified[0] ^= 1;
        var corrupted = new EncryptedContactEnvelope(modified, envelope.nonce(), envelope.keyVersion(),
                envelope.collectedAt());

        assertThatThrownBy(() -> cipher.decrypt(corrupted, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownKeyVersionAndMissingConfiguration() {
        var cipher = new AlternateContactStagingCipher(properties(1, Base64.getEncoder().encodeToString(KEY)),
                new ObjectMapper().findAndRegisterModules());
        var unknown = new EncryptedContactEnvelope(new byte[]{1}, new byte[12], 9, Instant.now());

        assertThatThrownBy(() -> cipher.decrypt(unknown, context()))
                .isInstanceOf(IllegalArgumentException.class);
        var unavailable = new AlternateContactStagingCipher(new AlternateContactCryptoProperties(), new ObjectMapper());
        assertThatThrownBy(() -> unavailable.encrypt(List.of(contact()), context()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envelopeAndContactToStringNeverContainValues() {
        var cipher = new AlternateContactStagingCipher(properties(1, Base64.getEncoder().encodeToString(KEY)),
                new ObjectMapper().findAndRegisterModules());
        var envelope = cipher.encrypt(List.of(contact()), context());

        assertThat(envelope.toString()).doesNotContain("Ana.Rios@example.com");
        assertThat(contact().toString()).doesNotContain("Ana.Rios@example.com");
    }

    private static AlternateContactCryptoProperties properties(int version, String key) {
        return new AlternateContactCryptoProperties(key, version, null, 0);
    }

    private static AlternateContactValue contact() {
        return new AlternateContactValue(ContactType.EMAIL, "Ana.Rios@example.com", Instant.EPOCH,
                "receipt", "alternate-contact-consent-v1");
    }

    private static StagingContext context() {
        return new StagingContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
