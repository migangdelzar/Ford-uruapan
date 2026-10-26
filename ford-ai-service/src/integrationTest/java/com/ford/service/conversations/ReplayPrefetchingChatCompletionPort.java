package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;

import java.time.Instant;
import java.util.Objects;

final class ReplayPrefetchingChatCompletionPort implements ChatCompletionPort {
    private static final String EVIDENCE_MARKER = "[REPLAY PREFETCHED COMPARISON EVIDENCE]";
    private static final String EVIDENCE_END = "[/REPLAY PREFETCHED COMPARISON EVIDENCE]";
    private static final String VEHICLE_EVIDENCE_MARKER = "[REPLAY PREFETCHED VEHICLE EVIDENCE]";
    private static final String VEHICLE_EVIDENCE_END = "[/REPLAY PREFETCHED VEHICLE EVIDENCE]";
    private static final String PROMOTION_EVIDENCE_MARKER = "[REPLAY PREFETCHED PROMOTION EVIDENCE]";
    private static final String PROMOTION_EVIDENCE_END = "[/REPLAY PREFETCHED PROMOTION EVIDENCE]";
    private static final int MAX_EVIDENCE_JSON_CHARS = 16_000;

    private final ChatCompletionPort delegate;
    private final LocalReplayComparisonPrefetcher prefetcher;
    private final ObjectMapper objectMapper;
    private final LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher;
    private final LocalReplayPromotionPrefetcher promotionPrefetcher;

    ReplayPrefetchingChatCompletionPort(ChatCompletionPort delegate,
                                         LocalReplayComparisonPrefetcher prefetcher,
                                         ObjectMapper objectMapper) {
        this(delegate, prefetcher, null, null, objectMapper);
    }

    ReplayPrefetchingChatCompletionPort(ChatCompletionPort delegate,
                                        LocalReplayComparisonPrefetcher prefetcher,
                                         LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher,
                                         ObjectMapper objectMapper) {
        this(delegate, prefetcher, vehicleFactPrefetcher, null, objectMapper);
    }

    ReplayPrefetchingChatCompletionPort(ChatCompletionPort delegate,
                                        LocalReplayComparisonPrefetcher prefetcher,
                                        LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher,
                                        LocalReplayPromotionPrefetcher promotionPrefetcher,
                                        ObjectMapper objectMapper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
        this.prefetcher = Objects.requireNonNull(prefetcher, "prefetcher is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper is required");
        this.vehicleFactPrefetcher = vehicleFactPrefetcher;
        this.promotionPrefetcher = promotionPrefetcher;
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        var prefetched = prefetcher.prefetch(request.message());
        if (prefetched.isEmpty()) {
            var promotionFollowUp = LocalReplayOfferFollowUp.requiresPromotionEvidence(
                    request.message(), request.decision() == null ? null : request.decision().toolFamily());
            if (promotionPrefetcher != null && promotionFollowUp) {
                var promotion = promotionPrefetcher.prefetch(request.message(), request.memoryContext());
                if (promotion.isPresent()) {
                    var value = promotion.orElseThrow();
                    var serialized = serialize(value);
                    if (serialized.length() <= MAX_EVIDENCE_JSON_CHARS) {
                        var enhanced = withMemoryContext(request, appendEvidence(request.memoryContext(), serialized,
                                PROMOTION_EVIDENCE_MARKER, PROMOTION_EVIDENCE_END));
                        tracePrefetch(request, context, "replay_prefetch_promotion_evidence", "list_promotions",
                                value.evidenceCount());
                        return delegate.complete(enhanced, context);
                    }
                }
            }
            if (vehicleFactPrefetcher == null || request.decision() == null
                    || request.decision().toolFamily() != ToolFamily.VEHICLE_DETAILS) {
                return delegate.complete(request, context);
            }
            var vehicleFact = vehicleFactPrefetcher.prefetch(request.message(), request.memoryContext());
            if (vehicleFact.isEmpty()) return delegate.complete(request, context);
            var fact = vehicleFact.orElseThrow();
            var serialized = serialize(fact);
            if (serialized.length() > MAX_EVIDENCE_JSON_CHARS) return delegate.complete(request, context);
            var enhanced = withMemoryContext(request,
                    appendEvidence(request.memoryContext(), serialized, VEHICLE_EVIDENCE_MARKER, VEHICLE_EVIDENCE_END));
            tracePrefetch(request, context, "replay_prefetch_vehicle_evidence", "get_vehicle", fact.evidenceCount());
            return delegate.complete(enhanced, context);
        }

        var comparison = prefetched.orElseThrow();
        var evidence = serialize(comparison);
        if (evidence.length() > MAX_EVIDENCE_JSON_CHARS) return delegate.complete(request, context);
        var enhancedRequest = withMemoryContext(request, appendEvidence(request.memoryContext(), evidence,
                EVIDENCE_MARKER, EVIDENCE_END));
        tracePrefetch(request, context, "replay_prefetch_comparison", "compare_trims", comparison.evidenceCount());
        return delegate.complete(enhancedRequest, context);
    }

    private String serialize(LocalReplayVehicleFactPrefetcher.PrefetchedVehicleFact fact) {
        try {
            return objectMapper.writeValueAsString(fact);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay vehicle evidence could not be serialized", failure);
        }
    }

    private String serialize(LocalReplayComparisonPrefetcher.PrefetchedComparison comparison) {
        try {
            return objectMapper.writeValueAsString(comparison);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay comparison evidence could not be serialized", failure);
        }
    }

    private String serialize(LocalReplayPromotionPrefetcher.PrefetchedPromotion promotion) {
        try { return objectMapper.writeValueAsString(promotion); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay promotion evidence could not be serialized", failure);
        }
    }

    private static String appendEvidence(String existingContext, String evidence, String marker, String end) {
        var prefix = existingContext == null || existingContext.isBlank() ? "" : existingContext.strip() + "\n\n";
        return prefix + marker + "\n" + evidence + "\n" + end;
    }

    private static AssistantRequest withMemoryContext(AssistantRequest request, String memoryContext) {
        return new AssistantRequest(request.tenantId(), request.conversationId(), request.message(),
                request.conversationState(), memoryContext, request.containsSensitiveData(), request.decision(),
                request.leadJourneyContext(), request.recentAssistantReplies(), request.repairInstruction(),
                request.traceCollector());
    }

    private static void tracePrefetch(AssistantRequest request, ToolContext context, String reason,
                                     String toolName, int evidenceCount) {
        DecisionTraceCollector collector = request.traceCollector() != null
                ? request.traceCollector() : context.traceCollector();
        if (collector == null) return;
        collector.record(new DecisionTraceEvent("tool", reason,
                "replay_prefetch", "none", toolName, "returned", evidenceCount, Instant.now()));
    }
}
