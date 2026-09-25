package com.ford.service.channels.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class MetaSignatureVerifier {

    public void verify(String body, String signature, String appSecret) {
        if (body == null || signature == null || appSecret == null || appSecret.isBlank()
                || !signature.startsWith("sha256=")) {
            throw new SecurityException("Invalid Meta signature");
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("Invalid Meta signature");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify Meta signature", exception);
        }
    }
}
