package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.MetaBindingStartupInitializer;
import com.ford.service.channels.adapter.in.meta.MetaWebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetaBindingStartupInitializerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void doesNothingWhenAutomaticBindingIsDisabled() {
        var jdbc = mock(JdbcClient.class);
        var jdbcClients = jdbcProvider(jdbc);
        var properties = new MetaWebhookProperties();

        new MetaBindingStartupInitializer(jdbcClients, properties).run(null);

        verifyNoInteractions(jdbc);
    }

    @Test
    void upsertsTheConfiguredWhatsAppBindingForCleanStartup() {
        var jdbc = mock(JdbcClient.class);
        var jdbcClients = jdbcProvider(jdbc);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        var properties = new MetaWebhookProperties();
        properties.getAutoBinding().setEnabled(true);
        properties.getAutoBinding().setTenantId(TENANT_ID);
        properties.getAutoBinding().setProviderAccountId("1254270961113240");

        new MetaBindingStartupInitializer(jdbcClients, properties).run(null);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        verify(statement).param("tenantId", TENANT_ID);
        verify(statement).param("providerAccountId", "1254270961113240");
        verify(statement).update();
        org.assertj.core.api.Assertions.assertThat(sql.getValue())
                .contains("insert into channel_binding")
                .contains("on conflict (channel, provider_account_id)");
        verify(statement).param("channel", "WHATSAPP");
    }

    @Test
    void skipsAutomaticBindingWhenTheProviderAccountIsNotConfigured() {
        var jdbc = mock(JdbcClient.class);
        var jdbcClients = jdbcProvider(jdbc);
        var properties = new MetaWebhookProperties();
        properties.getAutoBinding().setEnabled(true);
        properties.getAutoBinding().setTenantId(TENANT_ID);

        new MetaBindingStartupInitializer(jdbcClients, properties).run(null);

        verifyNoInteractions(jdbc);
    }

    @Test
    void createsOptionalMessengerBindingWithoutChangingWhatsAppConfiguration() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var properties = new MetaWebhookProperties();
        properties.getAutoBinding().setTenantId(TENANT_ID);
        properties.getMessengerBinding().setEnabled(true);
        properties.getMessengerBinding().setProviderAccountId("99887766");

        new MetaBindingStartupInitializer(jdbcProvider(jdbc), properties).initialize();

        verify(statement).param("tenantId", TENANT_ID);
        verify(statement).param("channel", "MESSENGER");
        verify(statement).param("providerAccountId", "99887766");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcClient> jdbcProvider(JdbcClient jdbc) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return provider;
    }
}
