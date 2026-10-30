package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.CatalogPasswordResolver;
import com.ford.service.catalog.adapter.out.CatalogSecretBundle;
import com.ford.service.catalog.adapter.out.CatalogSecretBundleClient;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogPasswordResolverTest {
    private final CatalogSecretBundleClient client = mock(CatalogSecretBundleClient.class);
    private final CatalogPasswordResolver resolver = new CatalogPasswordResolver(client);
    private static final String OCID = "ocid1.vaultsecret.oc1.iad.aaaa-secret";

    @Test void resolvesBase64Utf8Password() {
        when(client.getSecretBundle(OCID)).thenReturn(bundle("BASE64", Base64.getEncoder().encodeToString("reader-password".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertEquals("reader-password", resolver.resolve(OCID));
    }

    @Test void rejectsWrongContentType() {
        when(client.getSecretBundle(OCID)).thenReturn(bundle("JSON", "secret"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(OCID));
    }

    @Test void rejectsInvalidBase64() {
        when(client.getSecretBundle(OCID)).thenReturn(bundle("BASE64", "%%%"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(OCID));
    }

    @Test void rejectsInvalidUtf8() {
        when(client.getSecretBundle(OCID)).thenReturn(bundle("BASE64", "/w=="));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(OCID));
    }

    @Test void rejectsEmptyPassword() {
        when(client.getSecretBundle(OCID)).thenReturn(bundle("BASE64", ""));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(OCID));
    }

    @Test void rejectsMissingOcid() {
        assertThrows(IllegalStateException.class, () -> resolver.resolve(" "));
        verifyNoInteractions(client);
    }

    @Test void sanitizesOciClientFailure() {
        when(client.getSecretBundle(OCID)).thenThrow(new RuntimeException("response body secret-value"));
        var error = assertThrows(IllegalStateException.class, () -> resolver.resolve(OCID));
        assertFalse(error.getMessage().contains("secret-value"));
        assertNull(error.getCause());
    }

    private static CatalogSecretBundle bundle(String type, String content) {
        return new CatalogSecretBundle(type, content);
    }
}
