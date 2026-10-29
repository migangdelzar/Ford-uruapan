package com.ford.service.handoffcontact.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.EncryptedContactEnvelope;
import com.ford.service.handoffcontact.api.StagingContext;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public final class AlternateContactStagingCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<List<AlternateContactValue>> VALUE_LIST = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();
    private final int currentKeyVersion;
    private final Map<Integer, byte[]> keysByVersion;

    public AlternateContactStagingCipher(AlternateContactCryptoProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.currentKeyVersion = properties.getCurrentKeyVersion();
        this.keysByVersion = new HashMap<>();
        addKey(properties.getCurrentKeyBase64(), properties.getCurrentKeyVersion());
        addKey(properties.getPreviousKeyBase64(), properties.getPreviousKeyVersion());
    }

    public EncryptedContactEnvelope encrypt(List<AlternateContactValue> values, StagingContext context) {
        if (values == null || values.isEmpty() || values.size() > 1)
            throw new IllegalArgumentException("one consented contact is required");
        var key = keysByVersion.get(currentKeyVersion);
        if (key == null) throw new IllegalStateException("alternate contact staging encryption is unavailable");
        byte[] plaintext = null;
        try {
            plaintext = objectMapper.writeValueAsBytes(List.copyOf(values));
            var nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.clone(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(context));
            var ciphertext = cipher.doFinal(plaintext);
            return new EncryptedContactEnvelope(ciphertext, nonce, currentKeyVersion,
                    values.getFirst().consentedAt());
        } catch (JsonProcessingException | GeneralSecurityException exception) {
            throw new IllegalStateException("unable to encrypt alternate contact staging envelope");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public List<AlternateContactValue> decrypt(EncryptedContactEnvelope envelope, StagingContext context) {
        if (envelope == null || context == null) throw new IllegalArgumentException("staging envelope and context are required");
        var key = keysByVersion.get(envelope.keyVersion());
        if (key == null) throw new IllegalArgumentException("unknown alternate contact staging key version");
        byte[] plaintext = null;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.clone(), "AES"),
                    new GCMParameterSpec(TAG_BITS, envelope.nonce()));
            cipher.updateAAD(aad(context));
            plaintext = cipher.doFinal(envelope.ciphertext());
            var values = objectMapper.readValue(plaintext, VALUE_LIST);
            if (values == null || values.isEmpty() || values.size() > 1)
                throw new IllegalArgumentException("invalid alternate contact staging payload");
            return List.copyOf(values);
        } catch (AEADBadTagException exception) {
            throw new IllegalArgumentException("alternate contact staging authentication failed");
        } catch (GeneralSecurityException | java.io.IOException exception) {
            throw new IllegalArgumentException("alternate contact staging decryption failed");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void addKey(String encoded, int version) {
        if (encoded == null || encoded.isBlank()) return;
        if (version < 1) return;
        try {
            var key = Base64.getDecoder().decode(encoded);
            if (key.length == 32) keysByVersion.put(version, key);
            else Arrays.fill(key, (byte) 0);
        } catch (IllegalArgumentException ignored) {
            // Invalid optional secret leaves contact capture fail-closed without breaking ordinary messages.
        }
    }

    private static byte[] aad(StagingContext context) {
        if (context == null) throw new IllegalArgumentException("staging context is required");
        return ("alternate-contact-staging-v1|" + context.tenantId() + "|" + context.conversationId() + "|"
                + context.receiptId()).getBytes(StandardCharsets.UTF_8);
    }
}
