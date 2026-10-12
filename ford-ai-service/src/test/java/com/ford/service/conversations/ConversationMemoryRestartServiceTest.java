package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryRestartService;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.conversations.application.port.out.ConversationMemoryKeyRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ConversationMemoryRestartServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    void restartsBotMemoryForAuthorizedConversationAndReleasesLease() {
        var commands = mock(OperatorConversationCommandRepository.class);
        var keys = mock(ConversationMemoryKeyRepository.class);
        var leases = mock(ConversationTurnLeasePort.class);
        var memory = mock(ConversationMemoryService.class);
        var idempotency = mock(IdempotencyRepository.class);
        when(idempotency.find(eq(TENANT), eq("operator-1"), any(), eq("restart-1"))).thenReturn(Optional.empty());
        when(idempotency.reserve(eq(TENANT), eq("operator-1"), any(), eq("restart-1"), any(), any()))
                .thenReturn(true);
        when(commands.find(TENANT, CONVERSATION)).thenReturn(Optional.of(new OperatorConversationCommandRepository.CommandTarget(
                TENANT, CONVERSATION, Channel.WHATSAPP, "521", ConversationState.BOT_ACTIVE, 4)));
        when(leases.tryAcquire(eq(TENANT), eq(CONVERSATION), any(), any())).thenReturn(true);
        when(commands.bumpVersionForMemoryRestart(TENANT, CONVERSATION, 4)).thenReturn(true);
        var key = new ConversationMemoryKey(TENANT, Channel.WHATSAPP, "phone", "customer", CONVERSATION);
        when(keys.find(TENANT, CONVERSATION)).thenReturn(Optional.of(key));

        var service = new ConversationMemoryRestartService(commands, keys, leases, memory, idempotency,
                new ObjectMapper().findAndRegisterModules(), mock(org.springframework.context.ApplicationEventPublisher.class));
        var result = service.restart(operator(), TENANT, CONVERSATION, 4, "restart-1");

        assertThat(result.conversationVersion()).isEqualTo(5);
        assertThat(result.contextStartedAt()).isNotNull();
        verify(memory).resetContext(eq(key), any(UUID.class), eq(result.contextStartedAt()));
        verify(leases).release(eq(TENANT), eq(CONVERSATION), any());
        verify(idempotency).complete(eq(TENANT), eq("operator-1"), any(), eq("restart-1"), any(), any());
    }

    @Test
    void refusesRestartWhenBotTurnOwnsTheLease() {
        var commands = mock(OperatorConversationCommandRepository.class);
        var keys = mock(ConversationMemoryKeyRepository.class);
        var leases = mock(ConversationTurnLeasePort.class);
        var memory = mock(ConversationMemoryService.class);
        var idempotency = mock(IdempotencyRepository.class);
        when(idempotency.find(eq(TENANT), eq("operator-1"), any(), eq("restart-1"))).thenReturn(Optional.empty());
        when(idempotency.reserve(eq(TENANT), eq("operator-1"), any(), eq("restart-1"), any(), any()))
                .thenReturn(true);
        when(commands.find(TENANT, CONVERSATION)).thenReturn(Optional.of(new OperatorConversationCommandRepository.CommandTarget(
                TENANT, CONVERSATION, Channel.WHATSAPP, "521", ConversationState.BOT_ACTIVE, 4)));
        when(leases.tryAcquire(eq(TENANT), eq(CONVERSATION), any(), any())).thenReturn(false);

        var service = new ConversationMemoryRestartService(commands, keys, leases, memory, idempotency,
                new ObjectMapper().findAndRegisterModules(), mock(org.springframework.context.ApplicationEventPublisher.class));

        assertThatThrownBy(() -> service.restart(operator(), TENANT, CONVERSATION, 4, "restart-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("turn is active");
        verify(memory, never()).resetContext(any(), any(), any());
        verify(commands, never()).bumpVersionForMemoryRestart(any(), any(), any(Long.class));
        verify(leases, never()).release(any(), any(), any());
    }

    private static AuthenticatedOperator operator() {
        return new AuthenticatedOperator("operator-1", Set.of("SCOPE_operator:write"), Set.of("OPERATOR"), Set.of(TENANT));
    }
}
