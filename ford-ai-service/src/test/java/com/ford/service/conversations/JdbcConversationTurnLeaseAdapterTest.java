package com.ford.service.conversations;

import com.ford.service.conversations.adapter.out.JdbcConversationTurnLeaseAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcConversationTurnLeaseAdapterTest {

    @Test
    void acquiresAndReleasesOnlyTheTenantScopedConversationLease() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var adapter = new JdbcConversationTurnLeaseAdapter(jdbc);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();

        assertThat(adapter.tryAcquire(tenant, conversation, "worker-1", Duration.ofSeconds(45))).isTrue();
        adapter.release(tenant, conversation, "worker-1");

        verify(jdbc).sql(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("conversation_turn_lease") && sql.contains("tenant_id")
                        && sql.contains("lease_until")));
    }
}
