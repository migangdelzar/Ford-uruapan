package com.ford.service.channels.adapter.out;

import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.channels.application.port.out.ChannelBindingRepository;
import com.ford.service.channels.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcChannelBindingRepository implements ChannelBindingRepository {

    private final JdbcClient jdbc;

    public JdbcChannelBindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChannelBinding> findEnabledByProviderAccount(Channel channel, String providerAccountId) {
        return jdbc.sql("""
                select id, tenant_id, channel, provider_account_id, secret_reference, enabled
                from channel_binding
                where channel = :channel and provider_account_id = :providerAccountId and enabled = true
                """)
                .param("channel", channel.name())
                .param("providerAccountId", providerAccountId)
                .query((rs, rowNum) -> new ChannelBinding(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        Channel.valueOf(rs.getString("channel")),
                        rs.getString("provider_account_id"),
                        rs.getString("secret_reference"),
                        rs.getBoolean("enabled")))
                .optional();
    }

}
