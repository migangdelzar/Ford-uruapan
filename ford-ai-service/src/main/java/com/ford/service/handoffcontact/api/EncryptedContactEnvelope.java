package com.ford.service.handoffcontact.api;

import java.time.Instant;

/** Ciphertext-only storage envelope. Array accessors return defensive copies. */
public record EncryptedContactEnvelope(byte[] ciphertext, byte[] nonce, int keyVersion, Instant collectedAt) {
    public EncryptedContactEnvelope {
        ciphertext = ciphertext == null ? null : ciphertext.clone();
        nonce = nonce == null ? null : nonce.clone();
        if (ciphertext == null || nonce == null || collectedAt == null || keyVersion < 1)
            throw new IllegalArgumentException("invalid encrypted contact envelope");
    }

    @Override public byte[] ciphertext() { return ciphertext.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }

    @Override
    public String toString() {
        return "EncryptedContactEnvelope[ciphertext=redacted, nonce=redacted, keyVersion=" + keyVersion
                + ", collectedAt=" + collectedAt + "]";
    }
}
