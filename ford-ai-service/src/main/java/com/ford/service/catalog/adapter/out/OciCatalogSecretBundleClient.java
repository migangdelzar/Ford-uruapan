package com.ford.service.catalog.adapter.out;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;

public class OciCatalogSecretBundleClient implements CatalogSecretBundleClient {
    private final SecretsClient client;

    public OciCatalogSecretBundleClient(SecretsClient client) {
        this.client = client;
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
        } catch (BmcException ignored) {
            throw new IllegalStateException("Unable to retrieve catalog secret bundle from OCI Vault");
        }
    }
}
