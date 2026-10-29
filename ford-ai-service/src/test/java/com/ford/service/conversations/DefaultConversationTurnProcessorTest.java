package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.ai.application.CatalogNameIndexProperties;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.conversations.application.CustomerChoicePresentationService;
import com.ford.service.conversations.application.InventoryApprovalConversationService;
import com.ford.service.conversations.application.InventoryApprovalTriggerPolicy;
import com.ford.service.conversations.application.MessageApprovalIntakeService;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.DefaultConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.ResolvedInboundContent;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import com.ford.service.handoffcontact.api.StagingContext;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultConversationTurnProcessorTest {

    @Test
    void explicitHumanRequestUsesExistingHandoffPathBeforeAssistantRuns() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.human", "customer-1", "Quiero hablar con un asesor", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var approvals = mock(MessageApprovalIntakeService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(ConversationMemoryContext.empty(
                new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                        "phone-1", "customer-1", conversationId)));
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(commit.humanHandoffEnabled(tenantId)).thenReturn(true);
        when(commit.forceHumanHandoff(any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.HUMAN_PENDING);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                null, new CustomerChoicePresentationService(), new InventoryApprovalTriggerPolicy(), null, approvals);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var sanitizedInbound = org.mockito.ArgumentCaptor.forClass(AcceptedInboundMessage.class);
        verify(commit).forceHumanHandoff(eq(reference), sanitizedInbound.capture(), eq(snapshot), any(), any());
        assertThat(sanitizedInbound.getValue().text()).doesNotContain(inbound.text());
        assertThat(sanitizedInbound.getValue().attachments()).isEmpty();
        verifyNoInteractions(approvals);
        verifyNoInteractions(assistant);
    }

    @Test
    void mandatoryHandoffUnavailableLeavesAReasonedApprovalHoldAndNeverCallsAssistant() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.human-disabled", "customer-1",
                "Quiero hablar con una persona", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 3, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var approvals = mock(MessageApprovalIntakeService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(ConversationMemoryContext.empty(
                new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                        "phone-1", "customer-1", conversationId)));
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(commit.humanHandoffEnabled(tenantId)).thenReturn(false);
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                null, new CustomerChoicePresentationService(), new InventoryApprovalTriggerPolicy(), null, approvals);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.NEEDS_OPERATOR);
        verify(approvals).hold(eq(reference), eq(snapshot), org.mockito.ArgumentMatchers.argThat(disposition ->
                        disposition.reason() == com.ford.service.conversations.domain.AdvisorResponseDisposition.Reason.HUMAN_REQUEST),
                org.mockito.ArgumentMatchers.isNull(), eq(false));
        verify(commit, never()).forceHumanHandoff(any(), any(), any(), any(), any());
        verifyNoInteractions(assistant);
    }

    @Test
    void unsupportedDiscountPromiseIsHeldWithoutSendingOrRetainingUnsafeDraft() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.discount", "customer-1", "¿Qué promoción hay?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var approvals = mock(MessageApprovalIntakeService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(ConversationMemoryContext.empty(key));
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(assistant.respond(any())).thenReturn(new AssistantReply("Te aplicamos un descuento de $5,000.", false,
                null, null, List.of(
                new com.ford.service.ai.application.DecisionTraceEvent("evidence", "response_evidence_assessed",
                        null, "none", null, "supported", 1, Instant.now()))));
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                null, new CustomerChoicePresentationService(), new InventoryApprovalTriggerPolicy(), null, approvals);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.NEEDS_OPERATOR);
        verify(approvals).hold(eq(reference), eq(snapshot), org.mockito.ArgumentMatchers.argThat(disposition ->
                        disposition.reason() == com.ford.service.conversations.domain.AdvisorResponseDisposition.Reason.DISCOUNT_COMMITMENT),
                org.mockito.ArgumentMatchers.isNull(), eq(false));
        verify(commit).consecutiveSubstantiveFailures(tenantId, conversationId);
        verify(commit, never()).commit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void inventoryRequestRoutesToApprovalBeforeAssistantCanMakeClaim() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var text = "¿Tienen Ranger 2027 XLT en inventario?";
        var inbound = new AcceptedInboundMessage("wamid.stock", "customer-1", text, Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var memoryContext = ConversationMemoryContext.empty(new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var inventoryApproval = mock(InventoryApprovalConversationService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(text, InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(inventoryApproval.requestAndCommit(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.BOT_REPLY);
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                null, new CustomerChoicePresentationService(), new InventoryApprovalTriggerPolicy(), inventoryApproval);

        assertThat(processor.process(new ConversationTurnReference(tenantId, conversationId, receiptId)).route())
                .isEqualTo(ConversationGraphRoute.BOT_REPLY);

        verify(inventoryApproval).requestAndCommit(any(), eq(inbound), eq(snapshot), eq(memoryContext), any(), eq(text));
        verify(assistant, never()).respond(any());
        verify(commit).consecutiveSubstantiveFailures(tenantId, conversationId);
        verify(commit, never()).commit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void completedDuplicateRetriesStagingCleanupWithoutReprocessingTurn() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.completed", "customer-1", "Mensaje", Instant.now());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var intake = mock(AlternateContactIntakePort.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(true);
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                leadJourney, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, intake);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.DUPLICATE);

        verify(intake).delete(new StagingContext(tenantId, conversationId, receiptId));
        verifyNoInteractions(memory, leadJourney, assistant, commit, resolver);
    }

    @Test
    void sanitizesResolvedMediaBeforeTranscriptMemoryAndAssistantAndPassesOnlyStagedContactToCommit() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var original = new AcceptedInboundMessage("wamid.audio", "customer-1", "[audio]", Instant.now(),
                com.ford.service.channels.domain.InboundMessageKind.AUDIO, List.of());
        var resolvedRaw = "Autorizo compartir mi correo con Ford: Ana.Rios@example.com";
        var safeText = "Autorizo compartir mi correo con Ford: [dato de contacto omitido]";
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var memoryContext = new ConversationMemoryContext(key, "Contacto previo old@example.com", List.of(), List.of(), 0);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var intake = mock(AlternateContactIntakePort.class);
        var contact = new AlternateContactValue(ContactType.EMAIL, "Ana.Rios@example.com", Instant.now(),
                receiptId.toString(), "alternate-contact-consent-v1");
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(original));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.audio")).thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(original, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(resolvedRaw, InboundContentStatus.READY));
        when(intake.redact(resolvedRaw)).thenReturn(safeText);
        when(intake.redact("Gracias, quedó anotado.")).thenReturn("Gracias, quedó anotado.");
        when(intake.redact("Contacto previo old@example.com"))
                .thenReturn("Contacto previo [dato de contacto omitido]");
        when(intake.find(any(StagingContext.class), any(Instant.class))).thenReturn(List.of(contact));
        when(memory.load(any())).thenReturn(memoryContext);
        when(leadJourney.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenReturn(new AssistantReply("Gracias, quedó anotado.", false));
        when(commit.commit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                leadJourney, assistant, commit, resolver, null, com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, intake);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(intake).captureAndStage(new StagingContext(tenantId, conversationId, receiptId), resolvedRaw,
                original.receivedAt());
        verify(inboundRepository).persistResolvedContent(tenantId, conversationId, receiptId, safeText);
        verify(memory).recordInbound(any(), org.mockito.ArgumentMatchers.eq(receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.audio"), org.mockito.ArgumentMatchers.eq(safeText),
                org.mockito.ArgumentMatchers.eq(original.receivedAt()));
        var assistantRequest = org.mockito.ArgumentCaptor.forClass(com.ford.service.conversations.api.ConversationTurnRequest.class);
        verify(assistant).respond(assistantRequest.capture());
        assertThat(assistantRequest.getValue().message()).doesNotContain("Ana.Rios@example.com").isEqualTo(safeText);
        assertThat(assistantRequest.getValue().memory().summary()).doesNotContain("old@example.com");
        verify(commit).commit(org.mockito.ArgumentMatchers.eq(reference), org.mockito.ArgumentMatchers.eq(original),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.argThat(context ->
                        context.summary().equals("Contacto previo [dato de contacto omitido]")),
                org.mockito.ArgumentMatchers.eq(journey), org.mockito.ArgumentMatchers.any(AssistantReply.class),
                org.mockito.ArgumentMatchers.eq(List.of(contact)));
        verify(intake).delete(new StagingContext(tenantId, conversationId, receiptId));
    }

    @Test
    void loadsExactTenantScopedReceiptAndCallsExistingAssistantOnce() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.1", "customer-1", "¿Promociones?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var memoryContext = ConversationMemoryContext.empty(
                new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                        "phone-1", "customer-1", conversationId));
        var reply = new AssistantReply("Sí, te comparto las promociones vigentes.", false);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.1")).thenReturn(false);
        when(memory.load(any())).thenReturn(memoryContext);
        when(leadJourney.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenReturn(reply);
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                leadJourney, assistant, commit, resolver);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(inboundRepository).find(tenantId, conversationId, receiptId);
        verify(memory).recordInbound(any(), org.mockito.ArgumentMatchers.eq(receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq("¿Promociones?"),
                org.mockito.ArgumentMatchers.eq(inbound.receivedAt()));
        verify(assistant).respond(any());
        verify(commit).commit(reference, inbound, snapshot, memoryContext, journey, reply);
        InOrder order = org.mockito.Mockito.inOrder(resolver, memory, assistant);
        order.verify(resolver).resolve(inbound, tenantId, conversationId);
        order.verify(memory).recordInbound(any(), org.mockito.ArgumentMatchers.eq(receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq("¿Promociones?"),
                org.mockito.ArgumentMatchers.eq(inbound.receivedAt()));
        order.verify(assistant).respond(any());
    }

    @Test
    void duplicateTurnReturnsWithoutCallingModelAgain() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var resolver = mock(InboundContentResolutionService.class);
        var conversationRepository = mock(ConversationRepository.class);
        var assistant = mock(ConversationAssistant.class);
        when(inboundRepository.find(tenantId, conversationId, reference.receiptId()))
                .thenReturn(Optional.of(new AcceptedInboundMessage("wamid.duplicate", "customer-1", "Hola", Instant.now())));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.duplicate"))
                .thenReturn(true);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                mock(ConversationMemoryService.class), mock(LeadJourneyService.class), assistant,
                mock(ConversationTurnCommitService.class), resolver);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.DUPLICATE);
        verify(assistant, never()).respond(any());
    }

    @Test
    void assistantFailureDoesNotCommitJourneyOrOutboundEffects() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.failure", "customer-1", "Quiero una Ranger", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journeyService = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var memoryContext = ConversationMemoryContext.empty(key);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journeyService.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenThrow(new AssistantTurnUnavailableException(
                AssistantTurnUnavailableException.Disposition.RETRY));
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journeyService, assistant, commit, resolver);

        assertThatThrownBy(() -> processor.process(reference)).isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.RETRY));

        verify(commit, never()).commit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownInteractiveChoiceClarifiesWithoutCallingAssistant() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var text = "Opción elegida: Ranger 2027 XLT (vehicle-0000000000000000)";
        var inbound = new AcceptedInboundMessage("wamid.choice", "customer-1", text, Instant.now(),
                InboundMessageKind.INTERACTIVE, List.of());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var memoryContext = ConversationMemoryContext.empty(new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
        var catalog = mock(CatalogPort.class);
        when(catalog.nameIndexEntries()).thenReturn(List.of(new VehicleNameIndexEntry("Ranger", 2027, "XLT")));
        var indexProperties = new CatalogNameIndexProperties();
        var nameIndex = new CatalogNameIndexService(catalog, indexProperties, java.time.Clock.systemUTC());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(text, InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                nameIndex, new CustomerChoicePresentationService());

        assertThat(processor.process(new ConversationTurnReference(tenantId, conversationId, receiptId)).route())
                .isEqualTo(ConversationGraphRoute.BOT_REPLY);

        verify(assistant, never()).respond(any());
        var reply = org.mockito.ArgumentCaptor.forClass(AssistantReply.class);
        verify(commit).commit(any(), any(), any(), any(), any(), reply.capture());
        assertThat(reply.getValue().text()).isEqualTo(
                "No pude identificar esa opción vigente. ¿Qué modelo o versión le interesa?");
    }

    @Test
    void currentInteractiveChoiceBecomesCanonicalCatalogIdentityRequest() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var entry = new VehicleNameIndexEntry("Ranger", 2027, "XLT Diésel 4x4");
        var catalog = mock(CatalogPort.class);
        when(catalog.nameIndexEntries()).thenReturn(List.of(entry));
        var nameIndex = new CatalogNameIndexService(catalog, new CatalogNameIndexProperties(), java.time.Clock.systemUTC());
        var option = new CustomerChoicePresentationService().fromAnswer(
                "Ranger 2027 XLT Diésel 4x4", nameIndex.currentEntries()).getFirst();
        var text = "Opción elegida: " + option.title() + " (" + option.id() + ")";
        var inbound = new AcceptedInboundMessage("wamid.current-choice", "customer-1", text, Instant.now(),
                InboundMessageKind.INTERACTIVE, List.of());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var memoryContext = ConversationMemoryContext.empty(new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(resolver.resolve(inbound, tenantId, conversationId))
                .thenReturn(new ResolvedInboundContent(text, InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(assistant.respond(any())).thenReturn(new AssistantReply("Revisaré esa versión.", false));
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), true, null,
                nameIndex, new CustomerChoicePresentationService());

        assertThat(processor.process(new ConversationTurnReference(tenantId, conversationId, receiptId)).route())
                .isEqualTo(ConversationGraphRoute.BOT_REPLY);

        var request = org.mockito.ArgumentCaptor.forClass(com.ford.service.conversations.api.ConversationTurnRequest.class);
        verify(assistant).respond(request.capture());
        assertThat(request.getValue().message()).isEqualTo("Quiero revisar Ranger 2027 XLT Diésel 4x4.");
    }

    @Test
    void reviewWorthyAssistantFailureCommitsSafeFallbackInsteadOfSilentlyIgnoringTurn() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.review", "customer-1", "¿Qué promoción hay?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var memoryContext = ConversationMemoryContext.empty(
                new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                        "phone-1", "customer-1", conversationId));
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journeyService = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journeyService.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenThrow(new AssistantTurnUnavailableException(
                AssistantTurnUnavailableException.Disposition.REVIEW));
        when(commit.commitSafeFallback(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journeyService, assistant, commit, resolver);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);
        verify(commit).commitSafeFallback(org.mockito.ArgumentMatchers.eq(reference),
                org.mockito.ArgumentMatchers.eq(inbound), org.mockito.ArgumentMatchers.eq(snapshot),
                org.mockito.ArgumentMatchers.eq(memoryContext), org.mockito.ArgumentMatchers.eq(journey),
                org.mockito.ArgumentMatchers.contains("No pude confirmar"),
                org.mockito.ArgumentMatchers.eq("assistant_response_review"));
    }

    @Test
    void repeatedFinalReplyUsesSafeFallbackAndDurableReviewPath() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.repeat", "customer-1", "¿Qué consumo tiene la Ranger?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "phone-1", "customer-1", conversationId);
        var memoryContext = new ConversationMemoryContext(key, "", java.util.List.of(
                new com.ford.service.conversations.domain.ConversationMessage(UUID.randomUUID(), conversationId,
                        com.ford.service.conversations.domain.ConversationMessage.Direction.OUTBOUND,
                        "outbound:prior", "La Ranger está disponible para revisión en la agencia Ford.",
                        Instant.now())), java.util.List.of(), 1);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId())).thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(assistant.respond(any())).thenReturn(new AssistantReply("La Ranger está disponible para revisión en la agencia Ford.", false));

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journey, assistant, commit, resolver);

        when(commit.commitSafeFallback(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);
        verify(commit).commitSafeFallback(org.mockito.ArgumentMatchers.eq(reference),
                org.mockito.ArgumentMatchers.eq(inbound), org.mockito.ArgumentMatchers.eq(snapshot),
                org.mockito.ArgumentMatchers.eq(memoryContext), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains("No pude confirmar"),
                org.mockito.ArgumentMatchers.eq("repeated_response"));
    }

    @Test
    void rawExplorationModeCommitsRepeatedReplyWithoutReviewFallback() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var repeated = "La Ranger está disponible para revisión en la agencia Ford.";
        var inbound = new AcceptedInboundMessage("wamid.raw-repeat", "customer-1", "¿Qué consumo tiene la Ranger?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "phone-1", "customer-1", conversationId);
        var memoryContext = new ConversationMemoryContext(key, "", java.util.List.of(
                new com.ford.service.conversations.domain.ConversationMessage(UUID.randomUUID(), conversationId,
                        com.ford.service.conversations.domain.ConversationMessage.Direction.OUTBOUND,
                        "outbound:prior", repeated, Instant.now())), java.util.List.of(), 1);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journey = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId())).thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journey.load(tenantId, conversationId)).thenReturn(null);
        when(assistant.respond(any())).thenReturn(new AssistantReply(repeated, false));
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journey, assistant, commit, resolver, null,
                com.ford.service.conversations.application.port.out.ShadowDraftRecorder.disabled(),
                com.ford.service.conversations.application.ShadowReviewGate.disabled(), false);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(commit).commit(org.mockito.ArgumentMatchers.eq(reference), org.mockito.ArgumentMatchers.eq(inbound),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq(memoryContext),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        verify(commit, never()).commitSafeFallback(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unsupportedMediaCommitsSafeClarificationWithoutCallingAssistant() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var inbound = new AcceptedInboundMessage("wamid.unsupported", "customer-1", "",
                Instant.now(), com.ford.service.channels.domain.InboundMessageKind.DOCUMENT,
                java.util.List.of());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journeyService = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var memoryContext = ConversationMemoryContext.empty(key);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var safeReply = "No pude interpretar ese archivo con seguridad.";
        when(inboundRepository.find(tenantId, conversationId, reference.receiptId())).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(safeReply, InboundContentStatus.CLARIFICATION_REQUIRED));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journeyService.load(tenantId, conversationId)).thenReturn(journey);
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journeyService, assistant, commit, resolver);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(assistant, never()).respond(any());
        verify(commit).commit(org.mockito.ArgumentMatchers.eq(reference), org.mockito.ArgumentMatchers.eq(inbound),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq(memoryContext),
                org.mockito.ArgumentMatchers.eq(journey), org.mockito.ArgumentMatchers.argThat(reply ->
                        reply.text().equals(safeReply)));
    }

    @Test
    void persistsInterpretedMediaBeforeAddingResolvedTextToMemoryAndAssistantContext() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var inbound = new AcceptedInboundMessage("wamid.image", "customer-1", "¿Qué modelo es?",
                Instant.now(), com.ford.service.channels.domain.InboundMessageKind.IMAGE,
                java.util.List.of(new com.ford.service.channels.domain.InboundAttachment(
                        "media-id", "image/jpeg", null, 100L)));
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journeyService = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var memoryContext = ConversationMemoryContext.empty(key);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var resolvedText = "El vehículo parece una Ford Bronco; ¿desea que confirme la versión?";
        when(inboundRepository.find(tenantId, conversationId, reference.receiptId())).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(resolvedText, InboundContentStatus.READY));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journeyService.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenReturn(new AssistantReply("Parece una Bronco.", false));
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journeyService, assistant, commit, resolver);

        processor.process(reference);

        InOrder order = org.mockito.Mockito.inOrder(resolver, inboundRepository, memory, assistant);
        order.verify(resolver).resolve(inbound, tenantId, conversationId);
        order.verify(inboundRepository).persistResolvedContent(tenantId, conversationId,
                reference.receiptId(), resolvedText);
        order.verify(memory).recordInbound(any(), org.mockito.ArgumentMatchers.eq(reference.receiptId()),
                org.mockito.ArgumentMatchers.eq(inbound.providerMessageId()),
                org.mockito.ArgumentMatchers.eq(resolvedText), org.mockito.ArgumentMatchers.eq(inbound.receivedAt()));
        var request = org.mockito.ArgumentCaptor.forClass(com.ford.service.conversations.api.ConversationTurnRequest.class);
        verify(assistant).respond(request.capture());
        assertThat(request.getValue().message()).isEqualTo(resolvedText);
    }
}
