package com.ford.service.catalog.adapter.out;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;

public class OciCatalogSecretBundleClient implements CatalogSecretBundleClient {
    private final SecretsClient client;

    public OciCatalogSecretBundleClient(SecretsClient client) {
        this.client = client;
    }

    public static OciCatalogSecretBundleClient create() {
        return new OciCatalogSecretBundleClient(SecretsClient.builder()
                .build(InstancePrincipalsAuthenticationDetailsProvider.builder().build()));
    }

    @Override
    public CatalogSecretBundle getSecretBundle(String secretOcid) {
        try {
            var bundle = client.getSecretBundle(GetSecretBundleRequest.builder().secretId(secretOcid).build()).getSecretBundle();
            var content = bundle.getSecretBundleContent();
            if (content instanceof Base64SecretBundleContentDetails base64) {
                return new CatalogSecretBundle("BASE64", base64.getContent());
            }
            return new CatalogSecretBundle("UNSUPPORTED", null);
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("Unable to retrieve catalog secret bundle from OCI Vault");
        }
    }
}
