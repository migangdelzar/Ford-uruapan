package com.ford.service.channels.adapter.in.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

public class MetaBindingStartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetaBindingStartupInitializer.class);

    private final ObjectProvider<JdbcClient> jdbcClients;
    private final MetaWebhookProperties properties;

    public MetaBindingStartupInitializer(ObjectProvider<JdbcClient> jdbcClients,
                                         MetaWebhookProperties properties) {
        this.jdbcClients = jdbcClients;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        var binding = properties.getAutoBinding();
        if (!binding.isEnabled()) {
            return;
        }
        if (binding.getTenantId() == null || !isConfiguredProviderAccount(binding.getProviderAccountId())) {
            log.warn("Meta automatic binding skipped: configure a numeric provider-account-id and tenant-id "
                    + "before enabling it");
            return;
        }

        var jdbc = jdbcClients.getIfAvailable();
        if (jdbc == null) {
            log.warn("Meta automatic binding skipped: JDBC is not available");
            return;
        }

        jdbc.sql("""
                insert into channel_binding (
                    id, webhook_key_hash, tenant_id, channel, provider_account_id,
                    secret_reference, enabled, created_at, updated_at)
                values (:id, null, :tenantId, 'WHATSAPP', :providerAccountId,
                        null, true, now(), now())
                on conflict (channel, provider_account_id) do update
                set tenant_id = excluded.tenant_id,
                    enabled = true,
                    updated_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", binding.getTenantId())
                .param("providerAccountId", binding.getProviderAccountId().strip())
                .update();

        log.info("Ensured automatic Meta WhatsApp binding for provider account {} and tenant {}",
                binding.getProviderAccountId().strip(), binding.getTenantId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isConfiguredProviderAccount(String value) {
        return !isBlank(value) && value.strip().matches("\\d+");
    }
}
