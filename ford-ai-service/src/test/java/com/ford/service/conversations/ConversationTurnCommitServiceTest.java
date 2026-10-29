package com.ford.service.conversations;

import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.domain.Channel;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.ai.application.CatalogNameIndexProperties;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.conversations.application.CustomerChoicePresentationService;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.ConversationTurnState;
import com.ford.service.conversations.application.DecisionTraceRepository;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactValue.ContactType;
import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnCommitServiceTest {

    @Test
    void commitsOnlyCatalogNamesPresentInFinalCustomerTextAsReplyOptions() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("wamid.options", "customer-1", "Quiero ver opciones", Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "account", "customer-1", ConversationState.BOT_ACTIVE, 1, inbound.text(), inbound.providerMessageId());
        var memory = ConversationMemoryContext.empty(new ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "account", "customer-1", conversationId));
        var repository = mock(ConversationRepository.class);
        when(repository.completeBotTurn(eq(tenantId), eq(conversationId), eq(snapshot.version()),
                eq(inbound.providerMessageId()), any())).thenReturn(true);
        var catalog = mock(CatalogPort.class);
        when(catalog.nameIndexEntries()).thenReturn(List.of(
                new VehicleNameIndexEntry("Ranger", 2027, "XLT Gasolina 4x2")));
        var index = new CatalogNameIndexService(catalog, new CatalogNameIndexProperties(), java.time.Clock.systemUTC());
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                mock(LeadJourneyService.class), new HumanHandoffPolicy(false), mock(ConversationMemoryService.class),
                event -> { }, mock(ConversationTurnLedgerPort.class), mock(DecisionTraceRepository.class), null,
                index, new CustomerChoicePresentationService());

        service.commit(reference, inbound, snapshot, memory,
                new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()),
                new AssistantReply("Ranger 2027 XLT Gasolina 4x2. ¿Cuál desea revisar?", false));

        var turn = org.mockito.ArgumentCaptor.forClass(ConversationTurn.class);
        verify(repository).completeBotTurn(eq(tenantId), eq(conversationId), eq(snapshot.version()),
                eq(inbound.providerMessageId()), turn.capture());
        assertThat(turn.getValue().replyOptions()).singleElement().satisfies(option -> {
            assertThat(option.id()).matches("vehicle-[a-f0-9]{16}");
            assertThat(option.title()).isEqualTo("Ranger 2027 XLT Gasolina 4x2");
        });
        assertThat(turn.getValue().text()).isEqualTo("Ranger 2027 XLT Gasolina 4x2. ¿Cuál desea revisar?");
    }

    @Test
    void consentedAlternateContactIsAttachedOnlyToSavedAuthorizedHandoffPacket() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);
        var contact = new AlternateContactValue(ContactType.EMAIL, "Ana.Rios@example.com", Instant.now(),
                "receipt-id", "alternate-contact-consent-v1");
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                journey, new AssistantReply("Claro, registraré su solicitud.", false), List.of(contact));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var decision = org.mockito.ArgumentCaptor.forClass(HandoffDecision.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), decision.capture(), any());
        assertThat(decision.getValue().packet().alternateContact()).contains(contact);
        assertThat(decision.getValue().packet().preferredContactChannel())
                .contains(com.ford.service.handoffcontact.api.PreferredContactChannel.ALTERNATE_EMAIL);
        assertThat(fixture.events.events).filteredOn(com.ford.service.conversations.api.HandoffRequested.class::isInstance)
                .singleElement().satisfies(event -> assertThat(event.toString()).doesNotContain("Ana.Rios@example.com"));
    }

    @Test
    void acceptedHandoffDeletesStagingInsideCommitBoundaryAfterTicketSave() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);
        var staging = mock(AlternateContactStagingPort.class);
        var service = new ConversationTurnCommitService(fixture.repository, (id, messageId, decision) -> { },
                mock(LeadJourneyService.class), new HumanHandoffPolicy(true), mock(ConversationMemoryService.class),
                fixture.events, mock(ConversationTurnLedgerPort.class), mock(DecisionTraceRepository.class), staging);
        var contact = new AlternateContactValue(ContactType.EMAIL, "ana@example.com", Instant.now(),
                fixture.reference.receiptId().toString(), "alternate-contact-consent-v1");
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        var route = service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                journey, new AssistantReply("Entendido.", false), List.of(contact));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var order = org.mockito.Mockito.inOrder(fixture.repository, staging);
        order.verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), any());
        order.verify(staging).delete(fixture.tenantId, fixture.conversationId, fixture.reference.receiptId());
    }

    @Test
    void complaintCreatesPartialHandoffWithoutDependingOnModelDecision() {
        var fixture = handoffFixture("Estoy muy molesta por la atención que recibí.", List.of(), true);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Lamento lo ocurrido.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var decision = org.mockito.ArgumentCaptor.forClass(HandoffDecision.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), decision.capture(), any());
        assertThat(decision.getValue().packet().preferredContactChannel()).isEmpty();
        assertThat(fixture.events.events).filteredOn(com.ford.service.conversations.api.HandoffRequested.class::isInstance)
                .singleElement().satisfies(event -> {
                    var handoff = (com.ford.service.conversations.api.HandoffRequested) event;
                    assertThat(handoff.internalNote()).isNotNull();
                    assertThat(handoff.payload().arguments().keySet()).allMatch(java.util.Set.of(
                            "reason", "vehicle", "needs", "unresolvedQuestion")::contains);
                    assertThat(handoff.internalNote().toString()).doesNotContain("phone", "email", "payment");
        });
    }

    @Test
    void forcedHandoffWithoutAlternateContactDoesNotPersistWhatsAppPreference() {
        var fixture = handoffFixture("Quiero hablar con una persona.", List.of(), true);
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        org.mockito.Mockito.doReturn(false).when(fixture.repository).requestHumanHandoff(any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());

        assertThat(fixture.service.forceHumanHandoff(fixture.reference, fixture.inbound, fixture.snapshot,
                fixture.memory, journey)).isEqualTo(ConversationGraphRoute.DUPLICATE);

        var decision = org.mockito.ArgumentCaptor.forClass(HandoffDecision.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), decision.capture(), any());
        assertThat(decision.getValue().packet().preferredContactChannel()).isEmpty();
    }

    @Test
    void acceptedForcedHandoffUsesTheSanitizedPayloadReasonForTheNotification() {
        var fixture = handoffFixture("Quiero hablar con una persona.", List.of(), true);
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        var route = fixture.service.forceHumanHandoff(fixture.reference, fixture.inbound, fixture.snapshot,
                fixture.memory, journey);

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var events = fixture.events.events.stream()
                .filter(com.ford.service.conversations.api.HandoffRequested.class::isInstance)
                .map(com.ford.service.conversations.api.HandoffRequested.class::cast).toList();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.reason()).isEqualTo(event.payload().reason());
    }

    @Test
    void forcedHandoffDoesNotPersistFabricatedDecisionWhenNoAssistantRan() {
        var fixture = handoffFixture("Quiero hablar con una persona.", List.of(), true);
        var decisions = mock(ConversationDecisionRepository.class);
        var ledger = mock(ConversationTurnLedgerPort.class);
        var traces = mock(DecisionTraceRepository.class);
        var staging = mock(AlternateContactStagingPort.class);
        var service = new ConversationTurnCommitService(fixture.repository, decisions,
                mock(LeadJourneyService.class), new HumanHandoffPolicy(true), mock(ConversationMemoryService.class),
                fixture.events, ledger, traces, staging);
        var contact = new AlternateContactValue(ContactType.EMAIL, "person@example.com", Instant.now(),
                fixture.reference.receiptId().toString(), "alternate-contact-consent-v1");
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        var route = service.forceHumanHandoff(fixture.reference, fixture.inbound, fixture.snapshot,
                fixture.memory, journey, List.of(contact));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        org.mockito.Mockito.verifyNoInteractions(decisions);
        verify(ledger).recordOutcome(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.reference.receiptId()), eq(fixture.inbound.providerMessageId()),
                eq(ConversationTurnState.HUMAN_PENDING), eq(null), org.mockito.ArgumentMatchers.any(Instant.class));
        verify(traces).append(eq(fixture.tenantId), eq(fixture.conversationId), eq(fixture.reference.receiptId()),
                org.mockito.ArgumentMatchers.argThat(events -> events.size() == 1
                        && events.getFirst().stage().equals("handoff")
                        && events.getFirst().outcome().equals("queued")));
        var handoffDecision = org.mockito.ArgumentCaptor.forClass(HandoffDecision.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), handoffDecision.capture(), any());
        assertThat(handoffDecision.getValue().packet().alternateContact()).contains(contact);
        verify(staging).delete(fixture.tenantId, fixture.conversationId, fixture.reference.receiptId());
    }

    @Test
    void rejectedAssistantHandoffDoesNotPersistDecisionOutsideTheFailedCommit() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), false);
        var decisions = mock(ConversationDecisionRepository.class);
        var decision = new com.ford.service.ai.application.ConversationDecision(
                com.ford.service.ai.application.ConversationIntent.HUMAN_REQUEST,
                com.ford.service.ai.application.LeadTemperature.HOT,
                com.ford.service.ai.application.LeadStage.QUALIFIED,
                com.ford.service.ai.application.HandoffRecommendation.USER_REQUESTED,
                com.ford.service.ai.application.ToolFamily.NONE,
                com.ford.service.ai.application.SensitiveDataLevel.NONE,
                List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "HUMAN_REQUEST", 0.99,
                        "direct_customer_request", "test", "test-model", 1, false)));
        var service = new ConversationTurnCommitService(fixture.repository, decisions,
                mock(LeadJourneyService.class), new HumanHandoffPolicy(true), mock(ConversationMemoryService.class),
                fixture.events, mock(ConversationTurnLedgerPort.class), mock(DecisionTraceRepository.class));
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        var route = service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory, journey,
                new AssistantReply("Le ayudaré a contactar con un asesor.", false, decision));

        assertThat(route).isEqualTo(ConversationGraphRoute.DUPLICATE);
        org.mockito.Mockito.verifyNoInteractions(decisions);
    }

    @Test
    void secondRefusalToProvideQualificationDetailsCreatesPartialHandoff() {
        var prior = message(UUID.randomUUID(), ConversationMessage.Direction.INBOUND,
                "in-previous", "No quiero compartir mi nombre.");
        var fixture = handoffFixture("No quiero compartir mi nombre.", List.of(prior), true);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Entiendo.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), any());
    }

    @Test
    void handoffConfirmationNamesFordServiceQueueWithoutEta() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);

        fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Claro.", false));

        var confirmation = org.mockito.ArgumentCaptor.forClass(ConversationTurn.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), confirmation.capture());
        assertThat(confirmation.getValue().text()).contains("Ford Service");
        assertThat(confirmation.getValue().text().toLowerCase())
                .contains("quedó registrada en la fila")
                .doesNotContain("cuanto antes", "en breve", "en minutos", "notificad", "le contactar", "le llamar",
                        "asignad", "visto", "leído", "leido", "llamad");
    }

    @Test
    void rejectedHandoffEmitsNoCustomerCopyAndNoEvent() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), false);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Claro.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.DUPLICATE);
        assertThat(fixture.events.events).isEmpty();
        verify(fixture.repository, org.mockito.Mockito.never())
                .completeBotTurn(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void retriedHandoffReusesIdempotencyKeyAndDoesNotDuplicateEvent() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory, journey,
                new AssistantReply("Claro.", false));
        org.mockito.Mockito.when(fixture.repository.requestHumanHandoff(eq(fixture.tenantId),
                eq(fixture.conversationId), eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()),
                any(), any())).thenReturn(false);
        var retry = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory, journey,
                new AssistantReply("Claro.", false));

        assertThat(retry).isEqualTo(ConversationGraphRoute.DUPLICATE);
        assertThat(fixture.events.events)
                .filteredOn(com.ford.service.conversations.api.HandoffRequested.class::isInstance).hasSize(1);
    }

    @Test
    void safeFallbackCommitsReplyAndDurableReviewOutcome() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("provider-fallback", "customer-1", "¿Precio?", Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "account", "customer-1", ConversationState.BOT_ACTIVE, 1, inbound.text(), inbound.providerMessageId());
        var memory = ConversationMemoryContext.empty(
                new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "account", "customer-1", conversationId));
        var repository = mock(ConversationRepository.class);
        when(repository.completeBotTurn(tenantId, conversationId, snapshot.version(),
                inbound.providerMessageId(), new ConversationTurn("No pude confirmar esa información ahora. El equipo de Ford te ayudará en breve.")))
                .thenReturn(true);
        var ledger = mock(ConversationTurnLedgerPort.class);
        var traceRepository = mock(DecisionTraceRepository.class);
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                mock(LeadJourneyService.class), new HumanHandoffPolicy(false), mock(ConversationMemoryService.class),
                event -> { }, ledger, traceRepository);

        var route = service.commitSafeFallback(reference, inbound, snapshot, memory,
                new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()),
                "No pude confirmar esa información ahora. El equipo de Ford te ayudará en breve.",
                "invalid_model_output");

        assertThat(route).isEqualTo(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);
        verify(ledger).recordOutcome(eq(tenantId), eq(conversationId), eq(receiptId),
                eq(inbound.providerMessageId()), eq(ConversationTurnState.SAFE_FALLBACK_REVIEW),
                eq("invalid_model_output"), org.mockito.ArgumentMatchers.any(Instant.class));
        verify(ledger).recordReview(eq(tenantId), eq(conversationId), eq(receiptId),
                eq("invalid_model_output"), org.mockito.ArgumentMatchers.any(Instant.class));
        verify(traceRepository).append(eq(tenantId), eq(conversationId), eq(receiptId),
                org.mockito.ArgumentMatchers.argThat(events -> events.size() == 2
                        && events.getFirst().stage().equals("route")
                        && events.getLast().outcome().equals("fallback")));
    }

    private static HandoffFixture handoffFixture(String currentText, List<ConversationMessage> priorTurns,
                                                 boolean handoffAccepted) {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("in-current", "customer", currentText, Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "account", "customer", ConversationState.BOT_ACTIVE, 3, currentText, inbound.providerMessageId());
        var key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "account", "customer", conversationId);
        var memory = new ConversationMemoryContext(key, "", priorTurns, List.of(), 0);
        var repository = mock(ConversationRepository.class);
        when(repository.requestHumanHandoff(eq(tenantId), eq(conversationId), eq(snapshot.version()),
                eq(inbound.providerMessageId()), any(), any())).thenReturn(handoffAccepted);
        var events = new RecordingPublisher();
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                mock(LeadJourneyService.class), new HumanHandoffPolicy(true), mock(ConversationMemoryService.class),
                events);
        return new HandoffFixture(tenantId, conversationId, inbound, reference, snapshot, memory, repository,
                service, events);
    }

    private record HandoffFixture(UUID tenantId, UUID conversationId, AcceptedInboundMessage inbound,
                                  ConversationTurnReference reference, ConversationSnapshot snapshot,
                                  ConversationMemoryContext memory, ConversationRepository repository,
                                  ConversationTurnCommitService service, RecordingPublisher events) { }

    @Test
    void compactionFailureDoesNotPreventReplyCommitOrChangeCanonicalTranscript() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("wamid.current", "customer-1", "¿Cuánto queda por mes?",
                Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "provider-account", "customer-1", ConversationState.BOT_ACTIVE, 3, inbound.text(),
                inbound.providerMessageId());
        var key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "provider-account", "customer-1", conversationId);
        var memory = new ConversationMemoryContext(key, "último resumen válido", recentWindow(conversationId),
                List.of(), 7);
        var canonicalTranscript = new ArrayList<>(memory.recentMessages());
        canonicalTranscript.add(message(conversationId, ConversationMessage.Direction.INBOUND,
                inbound.providerMessageId(), inbound.text()));
        var originalTranscript = List.copyOf(canonicalTranscript);
        var repository = new RecordingRepository(snapshot, canonicalTranscript);
        var events = new RecordingPublisher();
        var properties = new MemoryProperties();
        properties.setPromptRecentMessageLimit(8);
        var memoryService = new ConversationMemoryService(new NoOpMemoryPort(), properties,
                new com.ford.service.conversations.application.NoOpConversationMemorySemanticPort(),
                new com.ford.service.conversations.application.NoOpConversationMemoryRecallPort(),
                event -> { },
                (tenant, conversation, summary, expired, maxChars) -> {
                    throw new IllegalStateException("provider unavailable");
                });
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                org.mockito.Mockito.mock(LeadJourneyService.class), new HumanHandoffPolicy(false),
                memoryService, events);
        var reply = new AssistantReply("Puedo ayudarle a revisar un pago mensual estimado.", false);

        var route = service.commit(reference, inbound, snapshot, memory,
                new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), reply);

        assertThat(route).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        assertThat(repository.completedTurn).isEqualTo(new ConversationTurn(reply.text()));
        assertThat(repository.canonicalTranscript).hasSize(originalTranscript.size() + 1);
        assertThat(repository.canonicalTranscript.subList(0, originalTranscript.size()))
                .containsExactlyElementsOf(originalTranscript);
        assertThat(repository.canonicalTranscript).last().satisfies(message -> {
            assertThat(message.direction()).isEqualTo(ConversationMessage.Direction.OUTBOUND);
            assertThat(message.content()).isEqualTo(reply.text());
        });
        assertThat(events.events).anySatisfy(event ->
                assertThat(event).isInstanceOfSatisfying(ConversationMemoryProjectionRefreshRequested.class,
                        projection -> assertThat(projection.update().summary())
                                .isEqualTo("último resumen válido")));
    }

    private static List<ConversationMessage> recentWindow(UUID conversationId) {
        var messages = new ArrayList<ConversationMessage>();
        for (var index = 0; index < 4; index++) {
            messages.add(message(conversationId, ConversationMessage.Direction.INBOUND,
                    "inbound-" + index, "Mensaje del cliente " + index));
            messages.add(message(conversationId, ConversationMessage.Direction.OUTBOUND,
                    "outbound-" + index, "Respuesta previa " + index));
        }
        return List.copyOf(messages);
    }

    private static ConversationMessage message(UUID conversationId, ConversationMessage.Direction direction,
                                               String providerMessageId, String content) {
        return new ConversationMessage(UUID.randomUUID(), conversationId, direction,
                providerMessageId, content, Instant.now());
    }

    private static final class NoOpMemoryPort implements ConversationMemoryPort {
        @Override
        public ConversationMemoryContext load(com.ford.service.conversations.domain.MemoryQuery query) {
            return ConversationMemoryContext.empty(query.key());
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
        }

        @Override
        public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                    MemoryProjectionUpdate update,
                                                    long expectedVersion) {
            return new MemoryUpdateResult(true, expectedVersion + 1);
        }
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    private static final class RecordingRepository implements ConversationRepository {
        private final ConversationSnapshot snapshot;
        private final List<ConversationMessage> canonicalTranscript;
        private ConversationTurn completedTurn;

        private RecordingRepository(ConversationSnapshot snapshot,
                                    List<ConversationMessage> canonicalTranscript) {
            this.snapshot = snapshot;
            this.canonicalTranscript = canonicalTranscript;
        }

        @Override
        public Optional<ConversationSnapshot> find(UUID conversationId) {
            return Optional.of(snapshot);
        }

        @Override
        public boolean completeBotTurn(UUID tenantId, UUID conversationId, long expectedVersion,
                                       String inboundProviderMessageId, ConversationTurn turn) {
            completedTurn = turn;
            canonicalTranscript.add(message(conversationId, ConversationMessage.Direction.OUTBOUND,
                    "outbound:" + inboundProviderMessageId, turn.text()));
            return true;
        }

        @Override
        public boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConversationState state(UUID conversationId) {
            return snapshot.state();
        }

        @Override
        public boolean recordMessageIfNew(UUID conversationId, String providerMessageId) {
            return true;
        }

        @Override
        public void transition(UUID conversationId, ConversationState expected, ConversationState target) {
            throw new UnsupportedOperationException();
        }
    }
}
