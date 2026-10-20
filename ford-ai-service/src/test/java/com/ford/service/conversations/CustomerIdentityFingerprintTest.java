package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerIdentityFingerprintTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final byte[] KEY_V1 = "01234567890123456789012345678901".getBytes();
    private static final byte[] KEY_V2 = "abcdefghijklmnopqrstuvwxyzABCDEF".getBytes();

    private final CustomerIdentityFingerprint fingerprint = new CustomerIdentityFingerprint();

    @Test
    void normalizesWhatsAppParticipantAndScopesFingerprintToTenantAndChannel() {
        assertThat(fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                "523331733925", 1, KEY_V1))
                .isEqualTo(fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                        "52 333 173 3925", 1, KEY_V1));
        assertThat(fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                "523331733925", 1, KEY_V1))
                .isNotEqualTo(fingerprint.fingerprint(TENANT_B, Channel.WHATSAPP,
                        "523331733925", 1, KEY_V1));
    }

    @Test
    void rotatesWithKeyVersionAndDoesNotNeedProviderAccountId() {
        var current = fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                "523331733925", 1, KEY_V1);
        var rotated = fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                "523331733925", 2, KEY_V2);

        assertThat(current).isNotEqualTo(rotated);
    }

    @Test
    void whatsappNormalizationKeepsDigitsOnly() {
        assertThat(fingerprint.normalizeParticipant("+52 (333) 173-3925"))
                .isEqualTo("523331733925");
    }

    @Test
    void rejectsMissingIdentifiersAndInvalidKeyVersions() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fingerprint.fingerprint(
                TENANT_A, Channel.WHATSAPP, "abc", 1, KEY_V1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fingerprint.fingerprint(
                TENANT_A, Channel.MESSENGER, "123", 1, KEY_V1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fingerprint.fingerprint(
                TENANT_A, Channel.WHATSAPP, "123", 0, KEY_V1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fingerprint.fingerprint(
                TENANT_A, Channel.WHATSAPP, "123", 1, "short".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesHmacSha256WithTenantAndChannelCanonicalScope() {
        var actual = fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP, "523331733925", 7, KEY_V1);
        var expected = hmac(TENANT_A + ":WHATSAPP:523331733925", KEY_V1);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void fingerprintsAreStableAcrossProviderAccountChanges() {
        var oldConversationKey = new com.ford.service.conversations.domain.ConversationMemoryKey(
                TENANT_A, Channel.WHATSAPP, "old-phone-number-id", "523331733925", UUID.randomUUID());
        var newConversationKey = new com.ford.service.conversations.domain.ConversationMemoryKey(
                TENANT_A, Channel.WHATSAPP, "new-phone-number-id", "+52 333 173 3925", UUID.randomUUID());

        assertThat(fingerprint.fingerprint(oldConversationKey.tenantId(), oldConversationKey.channel(),
                oldConversationKey.participantId(), 1, KEY_V1))
                .isEqualTo(fingerprint.fingerprint(newConversationKey.tenantId(), newConversationKey.channel(),
                        newConversationKey.participantId(), 1, KEY_V1));
        assertThat(oldConversationKey.conversationId()).isNotEqualTo(newConversationKey.conversationId());
    }

    @Test
    void participantPunctuationAndLettersCannotCreateAlternateIdentity() {
        assertThat(fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                "52+333-173-3925", 1, KEY_V1))
                .isEqualTo(fingerprint.fingerprint(TENANT_A, Channel.WHATSAPP,
                        "523331733925", 1, KEY_V1));
    }

    private static String hmac(String value, byte[] key) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }
}
