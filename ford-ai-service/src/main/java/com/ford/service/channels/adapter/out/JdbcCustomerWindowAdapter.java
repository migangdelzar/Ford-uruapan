package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCustomerWindowAdapter implements CustomerWindowPort {
    private final JdbcClient jdbc;

    public JdbcCustomerWindowAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerWindow> find(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select c.tenant_id, c.id, c.channel, max(m.received_at) as last_inbound_at
                  from conversation c
                  left join conversation_message m
                    on m.conversation_id = c.id and m.direction = 'INBOUND'
                 where c.tenant_id = :tenantId and c.id = :conversationId
                 group by c.tenant_id, c.id, c.channel
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, row) -> {
                    var last = rs.getTimestamp("last_inbound_at");
                    var inbound = last == null ? null : last.toInstant();
                    return new CustomerWindow(tenantId, conversationId,
                            Channel.valueOf(rs.getString("channel")), inbound,
                            inbound == null ? null : inbound.plus(Duration.ofHours(24)));
                }).optional();
    }
}
