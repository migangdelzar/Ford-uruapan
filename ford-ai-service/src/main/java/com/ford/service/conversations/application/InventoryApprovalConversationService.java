package com.ford.service.conversations.application;

import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.inventoryapproval.api.InventoryApprovalRequestPort;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class InventoryApprovalConversationService {
    private static final String CLARIFICATION = "¿Qué modelo o versión desea que confirme?";

    private final InventoryApprovalRequestPort approvals;
    private final CatalogNameIndexService nameIndex;
    private final CustomerChoicePresentationService choicePresenter;
    private final ConversationTurnCommitService commits;

    public InventoryApprovalConversationService(InventoryApprovalRequestPort approvals,
                                                @Nullable CatalogNameIndexService nameIndex,
                                                CustomerChoicePresentationService choicePresenter,
                                                ConversationTurnCommitService commits) {
        this.approvals = Objects.requireNonNull(approvals);
        this.nameIndex = nameIndex;
        this.choicePresenter = Objects.requireNonNull(choicePresenter);
        this.commits = Objects.requireNonNull(commits);
    }

    @Transactional
    public ConversationGraphRoute requestAndCommit(ConversationTurnReference reference,
                                                   AcceptedInboundMessage inbound,
                                                   ConversationSnapshot conversation,
                                                   ConversationMemoryContext memory,
                                                   LeadJourneySnapshot journey,
                                                   String resolvedText) {
        var entries = nameIndex == null ? List.<VehicleNameIndexEntry>of() : nameIndex.currentEntries();
        var options = choicePresenter.fromAnswer(resolvedText, entries);
        final String acknowledgement;
        if (options.size() != 1) {
            acknowledgement = CLARIFICATION;
        } else {
            var selectedTitle = options.getFirst().title();
            var selected = entries.stream().filter(InventoryApprovalConversationService::valid)
                    .filter(entry -> label(entry).equals(selectedTitle)).findFirst().orElse(null);
            if (selected == null) {
                acknowledgement = CLARIFICATION;
            } else {
                approvals.requestInventory(reference.tenantId(), reference.conversationId(), selected.model(),
                        selected.year(), selected.trim(), idempotencyKey(reference, inbound));
                acknowledgement = "Un asesor verificará la disponibilidad de " + label(selected) + " y le confirmará.";
            }
        }

        var route = commits.commit(reference, inbound, conversation, memory, journey,
                new AssistantReply(acknowledgement, false));
        if (route != ConversationGraphRoute.BOT_REPLY) {
            throw new IllegalStateException("inventory request acknowledgement was not committed");
        }
        return route;
    }

    private static boolean valid(VehicleNameIndexEntry entry) {
        return entry != null && entry.model() != null && entry.year() > 0;
    }

    private static String label(VehicleNameIndexEntry entry) {
        return entry.model() + " " + entry.year() + (entry.trim() == null ? "" : " " + entry.trim());
    }

    private static String idempotencyKey(ConversationTurnReference reference, AcceptedInboundMessage inbound) {
        try {
            var source = reference.tenantId() + ":" + reference.conversationId() + ":" + inbound.providerMessageId();
            var digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return "inventory-request:" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
