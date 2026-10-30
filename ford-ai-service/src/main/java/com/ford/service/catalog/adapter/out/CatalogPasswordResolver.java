package com.ford.service.catalog.adapter.out;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CatalogPasswordResolver {
    private final CatalogSecretBundleClient client;

    public CatalogPasswordResolver(CatalogSecretBundleClient client) {
        this.client = client;
    }

    public String resolve(String secretOcid) {
        if (secretOcid == null || secretOcid.isBlank()) {
            throw new IllegalStateException("Catalog Vault secret OCID is required");
        }
        final CatalogSecretBundle bundle;
        try {
            bundle = client.getSecretBundle(secretOcid);
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("Unable to retrieve catalog password from OCI Vault");
        }
        if (bundle == null || !"BASE64".equals(bundle.contentType()) || bundle.content() == null) {
            throw new IllegalStateException("OCI Vault returned an unsupported catalog password format");
        }
        try {
            var bytes = Base64.getDecoder().decode(bundle.content());
            var decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.isEmpty()) throw new IllegalStateException("OCI Vault returned an empty catalog password");
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException ignored) {
            throw new IllegalStateException("OCI Vault returned an invalid catalog password payload");
        }
    }
}
