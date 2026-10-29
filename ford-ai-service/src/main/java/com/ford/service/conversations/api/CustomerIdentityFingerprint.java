package com.ford.service.conversations.api;

import com.ford.service.channels.domain.Channel;
import org.springframework.modulith.NamedInterface;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@NamedInterface
public class CustomerIdentityFingerprint {

    public String fingerprint(UUID tenantId, Channel channel, String participantId,
                              int keyVersion, byte[] key) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(key, "key");
        if (channel != Channel.WHATSAPP) {
            throw new IllegalArgumentException("customer memory identity currently supports WhatsApp only");
        }
        if (keyVersion < 1) throw new IllegalArgumentException("keyVersion must be positive");
        if (key.length < 32) throw new IllegalArgumentException("HMAC key must contain at least 32 bytes");
        var normalizedParticipant = normalizeParticipant(participantId);
        if (normalizedParticipant.isBlank()) {
            throw new IllegalArgumentException("participantId must contain a valid identifier");
        }
        try {
            var canonical = tenantId + ":" + channel.name() + ":" + normalizedParticipant;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.clone(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    public String normalizeParticipant(String participantId) {
        if (participantId == null) throw new IllegalArgumentException("participantId is required");
        return participantId.replaceAll("\\D", "");
    }
}
