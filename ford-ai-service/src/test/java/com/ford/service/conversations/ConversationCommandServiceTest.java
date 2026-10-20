package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.conversations.application.DefaultConversationCommandService;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationCommandServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    void replaysSameIdempotentCommandWithoutAppendingTwice() {
        var repository = new InMemoryCommandRepository();
        var idempotency = new InMemoryIdempotencyRepository();
        var service = new DefaultConversationCommandService(repository, idempotency, new ObjectMapper().findAndRegisterModules());
        var operator = operator();
        var request = new SendMessageRequest("Hola", "client-1", false);

        var first = service.sendMessage(operator, TENANT, CONVERSATION, 0, "command-1", request);
        var replay = service.sendMessage(operator, TENANT, CONVERSATION, 0, "command-1", request);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.appended).isOne();
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPayload() {
        var repository = new InMemoryCommandRepository();
        var idempotency = new InMemoryIdempotencyRepository();
        var service = new DefaultConversationCommandService(repository, idempotency, new ObjectMapper().findAndRegisterModules());

        service.sendMessage(operator(), TENANT, CONVERSATION, 0, "command-1",
                new SendMessageRequest("Hola", null, false));

        assertThatThrownBy(() -> service.sendMessage(operator(), TENANT, CONVERSATION, 0, "command-1",
                new SendMessageRequest("Adiós", null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void rejectsStaleConversationVersionBeforeAppending() {
        var repository = new InMemoryCommandRepository();
        repository.target = new OperatorConversationCommandRepository.CommandTarget(
                TENANT, CONVERSATION, Channel.WHATSAPP, "521", ConversationState.BOT_ACTIVE, 4);
        var service = new DefaultConversationCommandService(repository, new InMemoryIdempotencyRepository(),
                new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> service.sendMessage(operator(), TENANT, CONVERSATION, 0, "command-1",
                new SendMessageRequest("Hola", null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        assertThat(repository.appended).isZero();
    }

    @Test
    void refusesOperatorSendWhenProviderBindingIsDisabled() {
        var repository = new InMemoryCommandRepository();
        repository.target = new OperatorConversationCommandRepository.CommandTarget(
                TENANT, CONVERSATION, Channel.WHATSAPP, "521", ConversationState.BOT_ACTIVE, 0, false);
        var service = new DefaultConversationCommandService(repository, new InMemoryIdempotencyRepository(),
                new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> service.sendMessage(operator(), TENANT, CONVERSATION, 0, "disabled-binding",
                new SendMessageRequest("Hola", null, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(repository.appended).isZero();
    }

    private static AuthenticatedOperator operator() {
        return new AuthenticatedOperator("operator-1", java.util.Set.of("SCOPE_operator:write"),
                java.util.Set.of("OPERATOR"), java.util.Set.of(TENANT));
    }

    private static final class InMemoryCommandRepository implements OperatorConversationCommandRepository {
        private CommandTarget target = new CommandTarget(TENANT, CONVERSATION, Channel.WHATSAPP,
                "521", ConversationState.BOT_ACTIVE, 0);
        private int appended;

        @Override
        public Optional<CommandTarget> find(UUID tenantId, UUID conversationId) {
            return target.tenantId().equals(tenantId) && target.conversationId().equals(conversationId)
                    ? Optional.of(target) : Optional.empty();
        }

        @Override
        public boolean messageExists(UUID tenantId, UUID conversationId, String providerMessageId) {
            return false;
        }

        @Override
        public boolean bumpVersionForMemoryRestart(UUID tenantId, UUID conversationId, long expectedVersion) {
            if (target.version() != expectedVersion) return false;
            target = new CommandTarget(target.tenantId(), target.conversationId(), target.channel(),
                    target.recipientId(), target.state(), target.version() + 1);
            return true;
        }

        @Override
        public CommandReceipt appendMessage(CommandMessage message) {
            appended++;
            target = new CommandTarget(target.tenantId(), target.conversationId(), target.channel(),
                    target.recipientId(), target.state(), target.version() + 1);
            return new CommandReceipt(message.messageId(), message.conversationId(), true,
                    message.internalNote() ? "INTERNAL" : "QUEUED", target.version(),
                    message.internalNote(), Instant.now());
        }
    }

    private static final class InMemoryIdempotencyRepository implements IdempotencyRepository {
        private final Map<String, StoredCommand> values = new HashMap<>();

        @Override
        public Optional<StoredCommand> find(UUID tenantId, String actorId, String operation, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public boolean reserve(UUID tenantId, String actorId, String operation, String key,
                               String requestHash, Instant createdAt) {
            return values.putIfAbsent(key, new StoredCommand(requestHash, null)) == null;
        }

        @Override
        public void complete(UUID tenantId, String actorId, String operation, String key,
                             String responseJson, Instant completedAt) {
            var current = values.get(key);
            values.put(key, new StoredCommand(current.requestHash(), responseJson));
        }
    }
}
