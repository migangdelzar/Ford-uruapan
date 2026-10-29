package com.ford.service.channels.adapter.in.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.ford.service.channels.domain.Channel;

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
        var jdbc = jdbcClients.getIfAvailable();
        var tenantId = properties.getTenantId();
        if (binding.isEnabled()) {
            ensureBinding(jdbc, Channel.WHATSAPP, binding.getProviderAccountId(), tenantId);
        }
        if (properties.getMessengerBinding().isEnabled()) {
            ensureBinding(jdbc, Channel.MESSENGER,
                    properties.getMessengerBinding().getProviderAccountId(), tenantId);
        }
        if (properties.getInstagramBinding().isEnabled()) {
            ensureBinding(jdbc, Channel.INSTAGRAM,
                    properties.getInstagramBinding().getProviderAccountId(), tenantId);
        }
    }

    private static void ensureBinding(JdbcClient jdbc, Channel channel, String providerAccountId, UUID tenantId) {
        if (tenantId == null || !isConfiguredProviderAccount(providerAccountId)) {
            log.warn("Meta automatic binding skipped: configure a numeric provider-account-id and tenant-id "
                    + "before enabling channel={}", channel);
            return;
        }
        if (jdbc == null) {
            log.warn("Meta automatic binding skipped: JDBC is not available for channel={}", channel);
            return;
        }
        jdbc.sql("""
                insert into channel_binding (
                    id, webhook_key_hash, tenant_id, channel, provider_account_id,
                    secret_reference, enabled, created_at, updated_at)
                values (:id, null, :tenantId, :channel, :providerAccountId,
                        null, true, now(), now())
                on conflict (channel, provider_account_id) do update
                set tenant_id = excluded.tenant_id,
                    enabled = true,
                    updated_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("channel", channel.name())
                .param("providerAccountId", providerAccountId.strip())
                .update();

        log.info("Ensured automatic Meta channel binding: channel={} providerAccountId={} tenantId={}",
                channel, providerAccountId.strip(), tenantId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isConfiguredProviderAccount(String value) {
        return !isBlank(value) && value.strip().matches("\\d+");
    }
}
