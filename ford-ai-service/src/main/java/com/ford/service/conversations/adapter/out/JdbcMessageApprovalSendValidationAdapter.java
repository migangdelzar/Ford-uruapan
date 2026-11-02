package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.conversations.application.AdvisorReplacementMessagePolicy;
import com.ford.service.conversations.application.port.out.MessageApprovalSendValidationPort;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Fresh, fail-closed checks immediately before the existing operator outbox command. */
@Component
public final class JdbcMessageApprovalSendValidationAdapter implements MessageApprovalSendValidationPort {
    private static final Duration MAX_DRAFT_AGE = Duration.ofMinutes(15);
    private final JdbcClient jdbc;
    private final AcceptedInboundMessageRepository inboundMessages;
    private final CustomerWindowPort customerWindows;
    private final Clock clock;
    private final AdvisorReplacementMessagePolicy editPolicy = new AdvisorReplacementMessagePolicy();

    public JdbcMessageApprovalSendValidationAdapter(JdbcClient jdbc,
                                                    AcceptedInboundMessageRepository inboundMessages,
                                                    CustomerWindowPort customerWindows,
                                                    Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.inboundMessages = Objects.requireNonNull(inboundMessages);
        this.customerWindows = Objects.requireNonNull(customerWindows);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void validate(MessageApproval approval, Action action, String exactText) {
        Objects.requireNonNull(approval);
        if (action != Action.APPROVE && action != Action.EDIT) {
            throw new IllegalArgumentException("only message-send actions can be validated");
        }
        var current = jdbc.sql("""
                select a.status, a.draft_text, a.draft_may_be_approved, a.conversation_version,
                       c.version, c.state, c.channel, coalesce(b.enabled, false) as binding_enabled,
                       (select m.id from channel_message m where m.tenant_id=c.tenant_id
                         and m.conversation_id=c.id order by m.received_at desc, m.id desc limit 1) as latest_receipt
                  from message_approval a
                  join conversation c on c.tenant_id=a.tenant_id and c.id=a.conversation_id
                  left join channel_binding b on b.tenant_id=c.tenant_id and b.channel=c.channel
                    and b.provider_account_id=c.provider_account_id
                 where a.tenant_id=:tenant and a.conversation_id=:conversation and a.id=:approval
                """)
                .param("tenant", approval.tenantId()).param("conversation", approval.conversationId())
                .param("approval", approval.id())
                .query((rs, row) -> new CurrentApproval(rs.getString("status"), rs.getString("draft_text"),
                        rs.getBoolean("draft_may_be_approved"), rs.getLong("conversation_version"),
                        rs.getLong("version"), rs.getString("state"), rs.getString("channel"),
                        rs.getBoolean("binding_enabled"), rs.getObject("latest_receipt", UUID.class))).optional()
                .orElseThrow(() -> new IllegalStateException("approval is no longer available"));

        var now = clock.instant();
        if (!"PENDING".equals(current.status()) || current.conversationVersion() != approval.conversationVersion()
                || current.conversationVersion() != current.conversationVersionOnConversation()
                || !"BOT_ACTIVE".equals(current.state()) && !"LEAD_CAPTURE".equals(current.state())
                || !current.bindingEnabled() || !approval.sourceReceiptId().equals(current.latestReceiptId())
                || !Objects.equals(approval.draftText(), current.draftText())
                || approval.draftMayBeApproved() != current.draftMayBeApproved()
                || now.isBefore(approval.createdAt()) || Duration.between(approval.createdAt(), now).compareTo(MAX_DRAFT_AGE) > 0) {
            throw new IllegalStateException("approval, evidence, or send authorization is stale");
        }
        var inbound = inboundMessages.find(approval.tenantId(), approval.conversationId(), approval.sourceReceiptId())
                .orElseThrow(() -> new IllegalStateException("approval source receipt is unavailable"));
        if (inbound.kind() != com.ford.service.channels.domain.InboundMessageKind.TEXT
                && inbound.kind() != com.ford.service.channels.domain.InboundMessageKind.INTERACTIVE
                && inbound.kind() != com.ford.service.channels.domain.InboundMessageKind.LOCATION) {
            throw new IllegalStateException("media-derived drafts require advisor takeover");
        }
        var window = customerWindows.find(approval.tenantId(), approval.conversationId())
                .filter(value -> value.channel().name().equals(current.channel()) && value.open(now))
                .orElseThrow(() -> new IllegalStateException("customer messaging window is closed or unavailable"));
        if (window.lastInboundAt() == null || window.lastInboundAt().isBefore(inbound.receivedAt())) {
            throw new IllegalStateException("approval source is not the current customer message");
        }
        if (action == Action.APPROVE) {
            if (!approval.draftMayBeApproved() || approval.draftText() == null
                    || !approval.draftText().equals(exactText)) {
                throw new IllegalStateException("only the exact, currently sendable draft can be approved");
            }
            if (editPolicy.containsUnsupportedCommitment(exactText)) {
                throw new IllegalStateException("draft contains a commitment without verified authority");
            }
            return;
        }
        var verdict = editPolicy.validate(approval.draftText(), approval.draftMayBeApproved(), inbound.text(), exactText);
        if (!verdict.allowed()) throw new IllegalStateException("advisor replacement failed safety validation: " + verdict.reasonCode());
    }

    private record CurrentApproval(String status, String draftText, boolean draftMayBeApproved,
                                   long conversationVersion, long conversationVersionOnConversation,
                                   String state, String channel, boolean bindingEnabled, UUID latestReceiptId) { }
}
