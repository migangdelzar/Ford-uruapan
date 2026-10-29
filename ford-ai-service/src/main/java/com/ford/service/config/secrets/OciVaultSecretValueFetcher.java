package com.ford.service.config.secrets;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class OciVaultSecretValueFetcher implements VaultSecretValueFetcher {

    private final SecretsClient secretsClient;

    public OciVaultSecretValueFetcher() {
        var provider = InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        this.secretsClient = SecretsClient.builder().build(provider);
        this.secretsClient.setRegion(provider.getRegion());
    }

    @Override
    public String fetchSecretValue(String secretId) {
        var response = secretsClient.getSecretBundle(GetSecretBundleRequest.builder()
                .secretId(secretId)
                .build());
        var content = response.getSecretBundle().getSecretBundleContent();
        if (!(content instanceof Base64SecretBundleContentDetails base64Content)) {
            throw new IllegalStateException("OCI Vault returned an unsupported secret content format");
        }
        var decoded = Base64.getDecoder().decode(base64Content.getContent());
        return new String(decoded, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        secretsClient.close();
    }
}
