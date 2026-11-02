package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.shared.AdvisorResponseRepeatPolicy;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ShadowDraftRecorder;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.StagingContext;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.time.Instant;

@Component
public class DefaultConversationTurnProcessor implements ConversationTurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationTurnProcessor.class);
    private static final String REVIEW_FALLBACK =
            "No pude confirmar esa información ahora. El equipo de Ford te ayudará en breve.";

    private final AcceptedInboundMessageRepository inboundRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemoryService memoryService;
    private final LeadJourneyService leadJourneyService;
    private final ConversationAssistant assistant;
    private final ConversationTurnCommitService commitService;
    private final InboundContentResolutionService contentResolutionService;
    private final DealershipResponsePlanner responsePlanner;
    private final ShadowDraftRecorder shadowRecorder;
    private final ShadowReviewGate shadowGate;
    private final boolean responseRepeatCheckEnabled;
    private final AlternateContactIntakePort alternateContacts;
    private final CatalogNameIndexService catalogNameIndex;
    private final CustomerChoicePresentationService choicePresenter;
    private final InventoryApprovalTriggerPolicy inventoryApprovalTrigger;
    private final InventoryApprovalConversationService inventoryApprovalConversation;
    private MessageApprovalIntakeService messageApprovalIntake;
    private final AdvisorResponseDispositionPolicy responseDispositionPolicy = new AdvisorResponseDispositionPolicy();
    private final AdvisorResponseRepeatPolicy repeatPolicy = new AdvisorResponseRepeatPolicy();

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, null, ShadowDraftRecorder.disabled(), ShadowReviewGate.disabled());
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            DealershipResponsePlanner responsePlanner) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, responsePlanner,
                ShadowDraftRecorder.disabled(), ShadowReviewGate.disabled());
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            ShadowDraftRecorder shadowRecorder,
                                            ShadowReviewGate shadowGate) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, null, shadowRecorder, shadowGate);
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            DealershipResponsePlanner responsePlanner,
                                            ShadowDraftRecorder shadowRecorder,
                                            ShadowReviewGate shadowGate) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, responsePlanner, shadowRecorder, shadowGate, true, null);
    }



    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            DealershipResponsePlanner responsePlanner,
                                            ShadowDraftRecorder shadowRecorder,
                                            ShadowReviewGate shadowGate,
                                              @org.springframework.beans.factory.annotation.Value(
                                                      "${ford.ai.response-repeat-check-enabled:true}")
                                              boolean responseRepeatCheckEnabled,
                                              AlternateContactIntakePort alternateContacts) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant, commitService,
                contentResolutionService, responsePlanner, shadowRecorder, shadowGate, responseRepeatCheckEnabled,
                alternateContacts, null, new CustomerChoicePresentationService());
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                             ConversationRepository conversationRepository,
                                             ConversationMemoryService memoryService,
                                             LeadJourneyService leadJourneyService,
                                             ConversationAssistant assistant,
                                             ConversationTurnCommitService commitService,
                                             InboundContentResolutionService contentResolutionService,
                                             DealershipResponsePlanner responsePlanner,
                                             ShadowDraftRecorder shadowRecorder,
                                             ShadowReviewGate shadowGate,
                                             @org.springframework.beans.factory.annotation.Value(
                                                     "${ford.ai.response-repeat-check-enabled:true}")
                                             boolean responseRepeatCheckEnabled,
                                             AlternateContactIntakePort alternateContacts,
                                             @org.springframework.lang.Nullable
                                             CatalogNameIndexService catalogNameIndex,
                                             CustomerChoicePresentationService choicePresenter) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant, commitService,
                contentResolutionService, responsePlanner, shadowRecorder, shadowGate, responseRepeatCheckEnabled,
                alternateContacts, catalogNameIndex, choicePresenter, new InventoryApprovalTriggerPolicy(), null);
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                             ConversationRepository conversationRepository,
                                             ConversationMemoryService memoryService,
                                             LeadJourneyService leadJourneyService,
                                             ConversationAssistant assistant,
                                             ConversationTurnCommitService commitService,
                                             InboundContentResolutionService contentResolutionService,
                                             DealershipResponsePlanner responsePlanner,
                                             ShadowDraftRecorder shadowRecorder,
                                             ShadowReviewGate shadowGate,
                                             @org.springframework.beans.factory.annotation.Value(
                                                     "${ford.ai.response-repeat-check-enabled:true}")
                                             boolean responseRepeatCheckEnabled,
                                             AlternateContactIntakePort alternateContacts,
                                             @org.springframework.lang.Nullable
                                             CatalogNameIndexService catalogNameIndex,
                                             CustomerChoicePresentationService choicePresenter,
                                             InventoryApprovalTriggerPolicy inventoryApprovalTrigger,
                                             @org.springframework.lang.Nullable
                                             InventoryApprovalConversationService inventoryApprovalConversation) {
        this.inboundRepository = Objects.requireNonNull(inboundRepository);
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.memoryService = Objects.requireNonNull(memoryService);
        this.leadJourneyService = Objects.requireNonNull(leadJourneyService);
        this.assistant = Objects.requireNonNull(assistant);
        this.commitService = Objects.requireNonNull(commitService);
        this.contentResolutionService = Objects.requireNonNull(contentResolutionService);
        this.responsePlanner = responsePlanner;
        this.shadowRecorder = Objects.requireNonNull(shadowRecorder);
        this.shadowGate = Objects.requireNonNull(shadowGate);
        this.responseRepeatCheckEnabled = responseRepeatCheckEnabled;
        this.alternateContacts = alternateContacts;
        this.catalogNameIndex = catalogNameIndex;
        this.choicePresenter = Objects.requireNonNull(choicePresenter);
        this.inventoryApprovalTrigger = Objects.requireNonNull(inventoryApprovalTrigger);
        this.inventoryApprovalConversation = inventoryApprovalConversation;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                             ConversationRepository conversationRepository,
                                             ConversationMemoryService memoryService,
                                             LeadJourneyService leadJourneyService,
                                             ConversationAssistant assistant,
                                             ConversationTurnCommitService commitService,
                                             InboundContentResolutionService contentResolutionService,
                                             DealershipResponsePlanner responsePlanner,
                                             ShadowDraftRecorder shadowRecorder,
                                             ShadowReviewGate shadowGate,
                                             @org.springframework.beans.factory.annotation.Value(
                                                     "${ford.ai.response-repeat-check-enabled:true}")
                                             boolean responseRepeatCheckEnabled,
                                             AlternateContactIntakePort alternateContacts,
                                             @org.springframework.lang.Nullable
                                             CatalogNameIndexService catalogNameIndex,
                                             CustomerChoicePresentationService choicePresenter,
                                             InventoryApprovalTriggerPolicy inventoryApprovalTrigger,
                                             @org.springframework.lang.Nullable
                                             InventoryApprovalConversationService inventoryApprovalConversation,
                                             MessageApprovalIntakeService messageApprovalIntake) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant, commitService,
                contentResolutionService, responsePlanner, shadowRecorder, shadowGate, responseRepeatCheckEnabled,
                alternateContacts, catalogNameIndex, choicePresenter, inventoryApprovalTrigger,
                inventoryApprovalConversation);
        this.messageApprovalIntake = Objects.requireNonNull(messageApprovalIntake);
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                             ConversationRepository conversationRepository,
                                             ConversationMemoryService memoryService,
                                             LeadJourneyService leadJourneyService,
                                             ConversationAssistant assistant,
                                             ConversationTurnCommitService commitService,
                                             InboundContentResolutionService contentResolutionService,
                                             DealershipResponsePlanner responsePlanner,
                                             ShadowDraftRecorder shadowRecorder,
                                             ShadowReviewGate shadowGate,
                                             boolean responseRepeatCheckEnabled) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant, commitService,
                contentResolutionService, responsePlanner, shadowRecorder, shadowGate, responseRepeatCheckEnabled, null);
    }

    @Override
    public ConversationTurnOutcome process(ConversationTurnReference reference) {
        return processTurn(reference);
    }

    private ConversationTurnOutcome processTurn(ConversationTurnReference reference) {
        var started = System.nanoTime();
        log.info("Conversation turn started: conversationId={} receiptId={}",
                reference.conversationId(), reference.receiptId());

        final AcceptedInboundMessage inbound;
        inbound = inboundRepository.find(reference.tenantId(), reference.conversationId(), reference.receiptId())
                .orElse(null);
        if (inbound == null) {

            log.info("Conversation turn skipped: conversationId={} receiptId={} reason={}",
                    reference.conversationId(), reference.receiptId(), "inbound_not_found");
            return new ConversationTurnOutcome(ConversationGraphRoute.NEEDS_OPERATOR);
        }

        if (conversationRepository.turnAlreadyCompleted(reference.tenantId(),
                reference.conversationId(), inbound.providerMessageId())) {
            if (alternateContacts != null) {
                alternateContacts.delete(new StagingContext(reference.tenantId(), reference.conversationId(),
                        reference.receiptId()));
            }
            log.info("Conversation turn skipped: conversationId={} receiptId={} reason=turn_already_completed",
                    reference.conversationId(), reference.receiptId());
            return new ConversationTurnOutcome(ConversationGraphRoute.DUPLICATE);
        }


        final ConversationSnapshot conversation;
        conversation = conversationRepository.find(reference.tenantId(), reference.conversationId())
                .filter(snapshot -> snapshot.tenantId().equals(reference.tenantId()))
                .orElse(null);
        if (conversation == null || !canUseAssistant(conversation.state())) {

            log.info("Conversation turn skipped: conversationId={} receiptId={} reason={} state={}",
                    reference.conversationId(), reference.receiptId(), conversation == null ? "conversation_not_found" : "assistant_not_allowed",
                    conversation == null ? "UNKNOWN" : conversation.state());
            return new ConversationTurnOutcome(ConversationGraphRoute.OUT_OF_SCOPE);
        }


        log.info("Conversation turn context loaded: conversationId={} state={} channel={} providerAccountId={}",
                reference.conversationId(), conversation.state(), conversation.channel(), conversation.providerAccountId());


        var resolved = contentResolutionService.resolve(inbound, reference.tenantId(), reference.conversationId());
        var resolvedText = resolved.text();
        var stagingContext = new StagingContext(reference.tenantId(), reference.conversationId(), reference.receiptId());
        if (alternateContacts != null && resolved.status() == InboundContentStatus.READY) {
            if (inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.IMAGE
                    || inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.AUDIO) {
                alternateContacts.captureAndStage(stagingContext, resolvedText, inbound.receivedAt());
            }
            resolvedText = alternateContacts.redact(resolvedText);
        }
        var safeResolved = new com.ford.service.ai.application.ResolvedInboundContent(resolvedText, resolved.status());
        if (inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.INTERACTIVE) {
            var currentEntries = catalogNameIndex == null ? java.util.List.<com.ford.service.catalog.api.VehicleNameIndexEntry>of()
                    : catalogNameIndex.currentEntries();
            var selected = choicePresenter.resolveSelection(safeResolved.text(), currentEntries);
            if (selected.isEmpty()) {
                safeResolved = new com.ford.service.ai.application.ResolvedInboundContent(
                        "No pude identificar esa opción vigente. ¿Qué modelo o versión le interesa?",
                        InboundContentStatus.CLARIFICATION_REQUIRED);
            } else {
                var entry = selected.get();
                var selectedLabel = entry.model() + " " + entry.year()
                        + (entry.trim() == null ? "" : " " + entry.trim());
                safeResolved = new com.ford.service.ai.application.ResolvedInboundContent(
                        "Quiero revisar " + selectedLabel + ".", InboundContentStatus.READY);
            }
        }
        if (resolved.status() == InboundContentStatus.READY
                && (inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.IMAGE
                || inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.AUDIO)) {
            inboundRepository.persistResolvedContent(reference.tenantId(), reference.conversationId(),
                    reference.receiptId(), resolvedText);
        }

        var stagedContacts = alternateContacts == null ? java.util.List.<AlternateContactValue>of()
                : alternateContacts.find(stagingContext, Instant.now());

        var memoryKey = new ConversationMemoryKey(reference.tenantId(), conversation.channel(),
                conversation.providerAccountId(), inbound.participantId(), reference.conversationId());
        memoryService.recordInbound(memoryKey, reference.receiptId(), inbound.providerMessageId(),
                resolvedText, inbound.receivedAt());
        var loadedMemory = memoryService.load(new MemoryQuery(memoryKey, resolvedText, inbound.providerMessageId()));
        var memory = alternateContacts == null ? loadedMemory : redactMemory(loadedMemory);
        var journey = leadJourneyService.load(reference.tenantId(), reference.conversationId());

        var consecutiveFailures = commitService.consecutiveSubstantiveFailures(reference.tenantId(),
                reference.conversationId());
        var inboundDisposition = responseDispositionPolicy.beforeAssistant(inbound, consecutiveFailures);
        if (inboundDisposition.route() == com.ford.service.conversations.domain.AdvisorResponseDisposition.Route.MANDATORY_HANDOFF) {
            return mandatoryHandoffOrHold(reference, inbound, conversation, memory, journey,
                    inboundDisposition, stagingContext, stagedContacts);
        }

        if (inventoryApprovalConversation != null && safeResolved.status() == InboundContentStatus.READY
                && inventoryApprovalTrigger.isInventoryRequest(safeResolved.text())) {
            var route = inventoryApprovalConversation.requestAndCommit(reference, inbound, conversation,
                    memory, journey, safeResolved.text());
            cleanupStagingAfterCompletion(stagingContext, route);
            return new ConversationTurnOutcome(route);
        }

        if (shadowGate.killSwitchEnabled()) {
            log.info("Shadow review kill switch active; routing turn to human queue: conversationId={} receiptId={}",
                    reference.conversationId(), reference.receiptId());
            var route = stagedContacts.isEmpty()
                    ? commitService.forceHumanHandoff(reference, inbound, conversation, memory, journey)
                    : commitService.forceHumanHandoff(reference, inbound, conversation, memory, journey, stagedContacts);
            cleanupStagingAfterCompletion(stagingContext, route);
            return new ConversationTurnOutcome(route);
        }
        log.info("Conversation turn invoking assistant: conversationId={} memoryVersion={} recentMessages={} journeyPresent={}",
                reference.conversationId(), memory.memoryVersion(), memory.recentMessages().size(), journey != null);
        AssistantReply reply;

        try {
            reply = safeResolved.status() == InboundContentStatus.CLARIFICATION_REQUIRED
                    ? new AssistantReply(safeResolved.text(), false)
                    : assistant.respond(new ConversationTurnRequest(reference.tenantId(),
                            reference.conversationId(), safeResolved.text(), conversation.state(), memory, journey));
        } catch (AssistantTurnUnavailableException unavailable) {

            if (unavailable.disposition() == AssistantTurnUnavailableException.Disposition.RETRY) {
                throw unavailable;
            }
            log.warn("Conversation turn recovery using safe fallback: conversationId={} receiptId={} disposition={}",
                    reference.conversationId(), reference.receiptId(), unavailable.disposition());
            var fallback = commitSafeFallback(reference, inbound, conversation, memory, journey,
                    "assistant_response_review");
            cleanupStagingAfterCompletion(stagingContext, fallback.route());
            return fallback;
        }

        if (responsePlanner != null) {
            reply = responsePlanner.apply(reply, resolvedText);
        }
        if (alternateContacts != null) {
            reply = new AssistantReply(alternateContacts.redact(reply.text()), reply.handoffRequired(),
                    reply.decision(), reply.journeyProposal(), reply.decisionTrace());
        }
        var recentCustomerMessages = memory.recentMessages().stream()
                .filter(message -> message.direction() == com.ford.service.conversations.domain.ConversationMessage.Direction.INBOUND)
                .map(com.ford.service.conversations.domain.ConversationMessage::content)
                .toList();
        var recentAssistantReplies = memory.recentMessages().stream()
                .filter(message -> message.direction() == com.ford.service.conversations.domain.ConversationMessage.Direction.OUTBOUND)
                .map(com.ford.service.conversations.domain.ConversationMessage::content)
                .toList();
        if (responseRepeatCheckEnabled) {
            var repeatVerdict = repeatPolicy.evaluate(resolvedText, recentCustomerMessages,
                    recentAssistantReplies, reply.text());
            if (repeatVerdict.repeated()) {

                log.warn("Conversation reply held by final repeat guard: conversationId={} receiptId={} reason={}",
                        reference.conversationId(), reference.receiptId(), repeatVerdict.reasonCode());
                var fallback = commitSafeFallback(reference, inbound, conversation, memory, journey, "repeated_response");
                cleanupStagingAfterCompletion(stagingContext, fallback.route());
                return fallback;
            }
        }

        if (safeResolved.status() != InboundContentStatus.CLARIFICATION_REQUIRED) {
            var responseDisposition = responseDispositionPolicy.afterAssistant(inbound, consecutiveFailures, reply);
            if (responseDisposition.route() != com.ford.service.conversations.domain.AdvisorResponseDisposition.Route.AUTOMATIC) {
                var evidenceEvents = reply.decisionTrace().stream()
                        .filter(event -> event.stage().equals("evidence")).toList();
                var supportedEvidence = !evidenceEvents.isEmpty() && evidenceEvents.stream()
                        .allMatch(event -> event.evidenceCount() != null && event.evidenceCount() > 0
                                && (event.outcome().equals("supported") || event.outcome().equals("resolved")));
                var mandatory = responseDisposition.route()
                        == com.ford.service.conversations.domain.AdvisorResponseDisposition.Route.MANDATORY_HANDOFF;
                if (mandatory) {
                    return mandatoryHandoffOrHold(reference, inbound, conversation, memory, journey,
                            responseDisposition, stagingContext, stagedContacts);
                }
                var requiresAuthority = switch (responseDisposition.reason()) {
                    case INVENTORY_PROMISE, DISCOUNT_COMMITMENT, FINANCE_COMMITMENT -> true;
                    default -> false;
                };
                var sendableDraft = supportedEvidence && !requiresAuthority;
                if (messageApprovalIntake != null) {
                    messageApprovalIntake.hold(reference, conversation, responseDisposition,
                            sendableDraft ? reply.text() : null, sendableDraft);
                }
                cleanupStagingAfterCompletion(stagingContext, ConversationGraphRoute.NEEDS_OPERATOR);
                return new ConversationTurnOutcome(ConversationGraphRoute.NEEDS_OPERATOR);
            }
        }

        if (shadowGate.shadowEnabled()) {
            shadowRecorder.record(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                    inbound.providerMessageId(), reply.text(), Instant.now());
            log.info("Shadow draft recorded without send: conversationId={} receiptId={} route=SHADOW_DRAFT",
                    reference.conversationId(), reference.receiptId());
            cleanupStagingAfterCompletion(stagingContext, ConversationGraphRoute.SHADOW_DRAFT);
            return new ConversationTurnOutcome(ConversationGraphRoute.SHADOW_DRAFT);
        }
        log.info("Conversation turn assistant completed: conversationId={} handoffRequired={} decisionPresent={} replyChars={}",
                reference.conversationId(), reply.handoffRequired(), reply.decision() != null,
                reply.text() == null ? 0 : reply.text().length());

        final ConversationGraphRoute route;
        route = stagedContacts.isEmpty()
                ? commitService.commit(reference, inbound, conversation, memory, journey, reply)
                : commitService.commit(reference, inbound, conversation, memory, journey, reply, stagedContacts);
        cleanupStagingAfterCompletion(stagingContext, route);

        log.info("Conversation turn committed: conversationId={} route={} durationMs={}",
                reference.conversationId(), route, elapsedMs(started));
        return new ConversationTurnOutcome(route);
    }

    private ConversationTurnOutcome commitSafeFallback(ConversationTurnReference reference,
                                                       com.ford.service.channels.api.AcceptedInboundMessage inbound,
                                                       com.ford.service.conversations.domain.ConversationSnapshot conversation,
                                                       com.ford.service.conversations.domain.ConversationMemoryContext memory,
                                                       com.ford.service.leads.domain.LeadJourneySnapshot journey,
                                                       String reasonCode) {
        var route = commitService.commitSafeFallback(reference, inbound, conversation, memory, journey,
                REVIEW_FALLBACK, reasonCode);
        return new ConversationTurnOutcome(route);
    }

    private void cleanupStagingAfterCompletion(StagingContext context, ConversationGraphRoute route) {
        if (alternateContacts != null && route != ConversationGraphRoute.DUPLICATE) alternateContacts.delete(context);
    }

    private ConversationTurnOutcome mandatoryHandoffOrHold(ConversationTurnReference reference,
                                                              AcceptedInboundMessage inbound,
                                                              ConversationSnapshot conversation,
                                                              ConversationMemoryContext memory,
                                                              LeadJourneySnapshot journey,
                                                               com.ford.service.conversations.domain.AdvisorResponseDisposition disposition,
                                                               StagingContext stagingContext,
                                                               java.util.List<AlternateContactValue> stagedContacts) {
        final ConversationGraphRoute route;
        if (commitService.humanHandoffEnabled(reference.tenantId())) {
            var safeInbound = new AcceptedInboundMessage(inbound.providerMessageId(), inbound.participantId(),
                    "Cliente requiere atención del equipo Ford.", inbound.receivedAt(), inbound.kind(),
                    java.util.List.of());
            route = stagedContacts == null || stagedContacts.isEmpty()
                    ? commitService.forceHumanHandoff(reference, safeInbound, conversation,
                            ConversationMemoryContext.empty(memory.key()), journey)
                    : commitService.forceHumanHandoff(reference, safeInbound, conversation,
                            ConversationMemoryContext.empty(memory.key()), journey, stagedContacts);
        } else {
            if (messageApprovalIntake != null) {
                messageApprovalIntake.hold(reference, conversation, disposition, null, false);
            }
            route = ConversationGraphRoute.NEEDS_OPERATOR;
        }
        cleanupStagingAfterCompletion(stagingContext, route);
        return new ConversationTurnOutcome(route);
    }

    private ConversationMemoryContext redactMemory(ConversationMemoryContext memory) {
        var messages = memory.recentMessages().stream().map(message -> new com.ford.service.conversations.domain.ConversationMessage(
                message.id(), message.conversationId(), message.direction(), message.providerMessageId(),
                alternateContacts.redact(message.content()), message.occurredAt())).toList();
        var facts = memory.durableFacts().stream().map(fact -> new com.ford.service.conversations.domain.MemoryFact(
                fact.kind(), alternateContacts.redact(fact.content()), fact.sourceMessageId())).toList();
        var recalled = memory.recalledTurns().stream().map(turn -> new com.ford.service.conversations.domain.ConversationMemoryRecall(
                turn.sourceMessageId(), turn.direction(), alternateContacts.redact(turn.content()),
                turn.occurredAt(), turn.similarity())).toList();
        return new ConversationMemoryContext(memory.key(), alternateContacts.redact(memory.summary()), messages, facts,
                memory.memoryVersion(), memory.updatedAt(), recalled, memory.contextStartedAt());
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static boolean canUseAssistant(ConversationState state) {
        return state == ConversationState.BOT_ACTIVE || state == ConversationState.LEAD_CAPTURE;
    }
}
