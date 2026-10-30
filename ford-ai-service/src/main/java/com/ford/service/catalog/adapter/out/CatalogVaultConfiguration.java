package com.ford.service.catalog.adapter.out;

import com.oracle.bmc.secrets.SecretsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ford.catalog.tools", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "ford.catalog.datasource", name = "secrets-provider", havingValue = "oci-vault")
public class CatalogVaultConfiguration {
    @Bean(destroyMethod = "close")
    SecretsClient catalogSecretsClient() {
        return SecretsClient.builder()
                .build(com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider.builder().build());
    }

    @Bean
    CatalogSecretBundleClient catalogSecretBundleClient(SecretsClient client) {
        return new OciCatalogSecretBundleClient(client);
    }

    @Bean
    CatalogPasswordResolver catalogPasswordResolver(CatalogSecretBundleClient client) {
        return new CatalogPasswordResolver(client);
    }
}
