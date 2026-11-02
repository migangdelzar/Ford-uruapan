package com.ford.service.inventoryapproval;

import com.ford.service.ai.application.CatalogNameIndexProperties;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.CustomerChoicePresentationService;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.application.InventoryApprovalConversationService;
import com.ford.service.inventoryapproval.application.InventoryApprovalService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryApprovalConversationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID receiptId = UUID.randomUUID();
    private final VehicleNameIndexEntry ranger = new VehicleNameIndexEntry("Ranger", 2027, "XLT Gasolina 4x2");
    private final CatalogPort catalog = mock(CatalogPort.class);
    private final CatalogNameIndexService index = new CatalogNameIndexService(catalog,
            new CatalogNameIndexProperties(), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    private final InventoryApprovalService approvals = mock(InventoryApprovalService.class);
    private final ConversationTurnCommitService commits = mock(ConversationTurnCommitService.class);
    private final InventoryApprovalConversationService service = new InventoryApprovalConversationService(
            approvals, index, new CustomerChoicePresentationService(), commits);
    private final ConversationTurnReference reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
    private final AcceptedInboundMessage inbound = new AcceptedInboundMessage("wamid.stock-1", "customer-1",
            "¿Tienen Ranger 2027 XLT Gasolina 4x2 en inventario?", NOW);
    private final ConversationSnapshot snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
            "phone-1", "customer-1", ConversationState.BOT_ACTIVE, 2, inbound.text(), inbound.providerMessageId());
    private final ConversationMemoryContext memory = ConversationMemoryContext.empty(new ConversationMemoryKey(
            tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
    private final LeadJourneySnapshot journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null,
            null, null, null, 0, null, 0, NOW);

    @Test
    void createsOnlyIdentityScopedTaskAndCommitsNeutralAcknowledgement() {
        when(catalog.nameIndexEntries()).thenReturn(List.of(ranger));
        when(commits.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        assertThat(service.requestAndCommit(reference, inbound, snapshot, memory, journey, inbound.text()))
                .isEqualTo(ConversationGraphRoute.BOT_REPLY);

        verify(approvals).requestInventory(eq(tenantId), eq(conversationId), eq("Ranger"), eq(2027), eq("XLT Gasolina 4x2"),
                org.mockito.ArgumentMatchers.argThat(key -> key.startsWith("inventory-request:")));
        var reply = org.mockito.ArgumentCaptor.forClass(AssistantReply.class);
        verify(commits).commit(any(), any(), any(), any(), any(), reply.capture());
        assertThat(reply.getValue().text()).isEqualTo(
                "Un asesor verificará la disponibilidad de Ranger 2027 XLT Gasolina 4x2 y le confirmará.");
        assertThat(reply.getValue().text()).doesNotContain("hay unidades", "está disponible");
    }

    @Test
    void asksForIdentityInsteadOfCreatingTaskWhenCatalogMatchIsMissingOrAmbiguous() {
        when(catalog.nameIndexEntries()).thenReturn(List.of(ranger,
                new VehicleNameIndexEntry("Ranger", 2027, "Wildtrak Diésel 4x4")));
        when(commits.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);
        var ambiguous = "¿Hay Ranger 2027 XLT Gasolina 4x2 y Ranger 2027 Wildtrak Diésel 4x4 en stock?";

        service.requestAndCommit(reference, inbound, snapshot, memory, journey, ambiguous);

        verify(approvals, never()).request(any(), any(), any(), any(), any(), any());
        var reply = org.mockito.ArgumentCaptor.forClass(AssistantReply.class);
        verify(commits).commit(any(), any(), any(), any(), any(), reply.capture());
        assertThat(reply.getValue().text()).isEqualTo("¿Qué modelo o versión desea que confirme?");
    }
}
