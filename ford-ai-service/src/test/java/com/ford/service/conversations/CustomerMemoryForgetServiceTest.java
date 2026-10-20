package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CustomerMemoryForgetReceipt;
import com.ford.service.conversations.application.CustomerMemoryForgetService;
import com.ford.service.conversations.application.CustomerMemoryService;
import com.ford.service.conversations.application.port.out.ConversationMemoryKeyRepository;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerMemoryForgetServiceTest {
    @Test
    void authorizedForgetResolvesConversationAndRemovesOnlySharedFacts() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var operator = new AuthenticatedOperator("operator", Set.of("SCOPE_operator:write"), Set.of("OPERATOR"), Set.of(tenant));
        var key = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "meta", "participant", conversation);
        var keys = mock(ConversationMemoryKeyRepository.class);
        var memory = mock(CustomerMemoryService.class);
        var idempotency = mock(IdempotencyRepository.class);
        when(keys.find(tenant, conversation)).thenReturn(Optional.of(key));
        when(idempotency.find(eq(tenant), eq("operator"), anyString(), anyString())).thenReturn(Optional.empty());
        when(idempotency.reserve(eq(tenant), eq("operator"), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(memory.forget(eq(tenant), eq(Channel.WHATSAPP), eq(conversation), any(), any(), eq("operator")))
                .thenReturn(new com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository.ForgetResult(
                        2, Instant.now()));
        var service = new CustomerMemoryForgetService(memory, keys, idempotency, new ObjectMapper().findAndRegisterModules());

        var receipt = service.forget(operator, tenant, conversation, "forget-1");

        assertThat(receipt.conversationId()).isEqualTo(conversation);
        assertThat(receipt.removedFactCount()).isEqualTo(2);
        verify(memory).forget(eq(tenant), eq(Channel.WHATSAPP), eq(conversation), any(), any(), eq("operator"));
        verify(idempotency).complete(eq(tenant), eq("operator"), eq("conversation.customer-memory.forget"),
                eq("forget-1"), anyString(), any());
    }

    @Test
    void rejectsUnauthorizedForgetBeforeIdentityLookup() {
        var keys = mock(ConversationMemoryKeyRepository.class);
        var service = new CustomerMemoryForgetService(mock(CustomerMemoryService.class), keys,
                mock(IdempotencyRepository.class), new ObjectMapper());
        var tenant = UUID.randomUUID();
        var operator = new AuthenticatedOperator("reader", Set.of("SCOPE_operator:read"), Set.of("OPERATOR"), Set.of(tenant));

        assertThatThrownBy(() -> service.forget(operator, tenant, UUID.randomUUID(), "forget-2"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(keys);
    }

    @Test
    void idempotentReplayReturnsSameForgetReceiptWithoutDeletingAgain() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var operator = new AuthenticatedOperator("operator", Set.of("SCOPE_operator:write"), Set.of("OPERATOR"), Set.of(tenant));
        var key = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "meta", "participant", conversation);
        var keys = mock(ConversationMemoryKeyRepository.class);
        when(keys.find(tenant, conversation)).thenReturn(Optional.of(key));
        var memory = mock(CustomerMemoryService.class);
        when(memory.forget(eq(tenant), eq(Channel.WHATSAPP), eq(conversation), any(), any(), eq("operator")))
                .thenReturn(new com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository.ForgetResult(
                        2, Instant.now()));
        var completedJson = new java.util.concurrent.atomic.AtomicReference<String>();
        var idempotency = new IdempotencyRepository() {
            private boolean reserved;
            private String requestHash;
            @Override public Optional<StoredCommand> find(UUID t, String actor, String operation, String commandKey) {
                return completedJson.get() == null ? Optional.empty()
                        : Optional.of(new StoredCommand(requestHash, completedJson.get()));
            }
            @Override public boolean reserve(UUID t, String actor, String operation, String commandKey,
                                             String hash, Instant createdAt) {
                if (reserved) return false;
                reserved = true;
                requestHash = hash;
                return true;
            }
            @Override public void complete(UUID t, String actor, String operation, String commandKey,
                                           String responseJson, Instant completedAt) {
                completedJson.set(responseJson);
            }
        };
        var service = new CustomerMemoryForgetService(memory, keys, idempotency,
                new ObjectMapper().findAndRegisterModules());

        var first = service.forget(operator, tenant, conversation, "forget-replay");
        var replay = service.forget(operator, tenant, conversation, "forget-replay");

        assertThat(replay).isEqualTo(first);
        verify(memory, times(1)).forget(eq(tenant), eq(Channel.WHATSAPP), eq(conversation), any(), any(), eq("operator"));
    }
}
