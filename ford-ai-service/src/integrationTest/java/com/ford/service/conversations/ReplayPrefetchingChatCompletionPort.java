package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.PrefetchedComparisonEvidence;
import com.ford.service.ai.application.port.out.ChatCompletionPort;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ReplayPrefetchingChatCompletionPort implements ChatCompletionPort {
    private static final Logger log = LoggerFactory.getLogger(ReplayPrefetchingChatCompletionPort.class);
    private static final String EVIDENCE_MARKER = "[REPLAY PREFETCHED COMPARISON EVIDENCE]";
    private static final String EVIDENCE_END = "[/REPLAY PREFETCHED COMPARISON EVIDENCE]";
    private static final String VEHICLE_EVIDENCE_MARKER = "[REPLAY PREFETCHED VEHICLE EVIDENCE]";
    private static final String VEHICLE_EVIDENCE_END = "[/REPLAY PREFETCHED VEHICLE EVIDENCE]";
    private static final String PROMOTION_EVIDENCE_MARKER = "[REPLAY PREFETCHED PROMOTION EVIDENCE]";
    private static final String PROMOTION_EVIDENCE_END = "[/REPLAY PREFETCHED PROMOTION EVIDENCE]";
    private static final int MAX_EVIDENCE_JSON_CHARS = 16_000;
    private static final int MAX_REPLAY_BUYER_MESSAGES = 20;

    private final ChatCompletionPort delegate;
    private final LocalReplayComparisonPrefetcher prefetcher;
    private final ObjectMapper objectMapper;
    private final LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher;
    private final LocalReplayPromotionPrefetcher promotionPrefetcher;
    private final LocalReplayPrecisionEvidence precisionEvidence;
    private final java.util.function.Supplier<String> snapshotSha256;
    private final Map<UUID, ArrayDeque<String>> buyerMessagesByConversation = new HashMap<>();
    private final Map<UUID, LocalComparisonPairResolver.Pair> latestExactBuyerPairByConversation = new HashMap<>();

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
        this(delegate, prefetcher, vehicleFactPrefetcher, promotionPrefetcher, objectMapper, null);
    }

    ReplayPrefetchingChatCompletionPort(ChatCompletionPort delegate,
                                         LocalReplayComparisonPrefetcher prefetcher,
                                         LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher,
                                         LocalReplayPromotionPrefetcher promotionPrefetcher,
                                          ObjectMapper objectMapper,
                                          LocalReplayPrecisionEvidence precisionEvidence) {
        this(delegate, prefetcher, vehicleFactPrefetcher, promotionPrefetcher, objectMapper, precisionEvidence, null);
    }

    ReplayPrefetchingChatCompletionPort(ChatCompletionPort delegate,
                                        LocalReplayComparisonPrefetcher prefetcher,
                                        LocalReplayVehicleFactPrefetcher vehicleFactPrefetcher,
                                        LocalReplayPromotionPrefetcher promotionPrefetcher,
                                        ObjectMapper objectMapper,
                                        LocalReplayPrecisionEvidence precisionEvidence,
                                        java.util.function.Supplier<String> snapshotSha256) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
        this.prefetcher = Objects.requireNonNull(prefetcher, "prefetcher is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper is required");
        this.vehicleFactPrefetcher = vehicleFactPrefetcher;
        this.promotionPrefetcher = promotionPrefetcher;
        this.precisionEvidence = precisionEvidence;
        this.snapshotSha256 = snapshotSha256;
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        if (delegate instanceof LocalQwenSeededChatCompletionPort seeded && seeded.isRecordedSeedReply(request)) {
            return delegate.complete(request, context);
        }
        if ("MEMORY_COMPACTION".equals(request.conversationState())) {
            return delegate.complete(request, context);
        }
        if (precisionEvidence != null) precisionEvidence.beginTurn(request.conversationId());
        var buyerHistory = rememberBuyerMessageAndGetPriorContext(request);
        prefetcher.resolveExactBuyerPair(request.message()).ifPresent(pair -> {
            synchronized (this) {
                latestExactBuyerPairByConversation.put(request.conversationId(), pair);
            }
        });
        var rememberedBuyerPair = latestExactBuyerPair(request.conversationId());
        var exactCurrentPair = prefetcher.resolveExactBuyerPair(request.message());
        var pairFromHistory = prefetcher.resolvePairFromHistory(request.message(), buyerHistory);
        var prefetchResult = prefetcher.prefetchResult(request.message(), buyerHistory, rememberedBuyerPair);
        log.info("Local replay comparison boundary: messageSha256={} messageChars={} comparisonIntent={} "
                        + "currentPairPresent={} historyPairPresent={} rememberedPairPresent={} status={} evidenceCount={}",
                safeFingerprint(request.message()), request.message() == null ? 0 : request.message().length(),
                prefetcher.isComparisonIntent(request.message()), exactCurrentPair.isPresent(),
                pairFromHistory.isPresent(), rememberedBuyerPair != null, prefetchResult.status(),
                prefetchResult.comparison() == null ? 0 : prefetchResult.comparison().evidenceCount());
        var comparisonStatus = switch (prefetchResult.status()) {
            case NOT_A_COMPARISON -> LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.NOT_A_COMPARISON;
            case PAIR_UNRESOLVED -> LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.PAIR_UNRESOLVED;
            case CATALOG_RESULT_INCOMPLETE ->
                    LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.CATALOG_RESULT_INCOMPLETE;
            case READY -> null;
        };
        if (comparisonStatus != null && precisionEvidence != null) {
            precisionEvidence.recordComparisonStatus(request.conversationId(), comparisonStatus);
        }
        if (prefetchResult.status() == LocalReplayComparisonPrefetcher.Status.PAIR_UNRESOLVED
                || prefetchResult.status() == LocalReplayComparisonPrefetcher.Status.CATALOG_RESULT_INCOMPLETE) {
            return delegate.complete(request, context);
        }
        if (prefetchResult.status() == LocalReplayComparisonPrefetcher.Status.NOT_A_COMPARISON) {
            var promotionFollowUp = LocalReplayOfferFollowUp.requiresPromotionEvidence(
                    request.message(), request.decision() == null ? null : request.decision().toolFamily());
            if (promotionPrefetcher != null && promotionFollowUp) {
                var promotion = promotionPrefetcher.prefetch(request.message(), buyerHistory);
                if (promotion.isPresent()) {
                    var value = promotion.orElseThrow();
                    var serialized = serialize(value);
                    if (serialized.length() <= MAX_EVIDENCE_JSON_CHARS) {
                        recordPrecisionEvidence(request, "list_promotions", value.evidenceCount(), serialized);
                        var enhanced = withMemoryContext(request, appendEvidence(request.memoryContext(), serialized,
                                PROMOTION_EVIDENCE_MARKER, PROMOTION_EVIDENCE_END));
                        tracePrefetch(request, context, "replay_prefetch_promotion_evidence", "list_promotions",
                                value.evidenceCount());
                        return delegate.complete(enhanced, context);
                    }
                }
            }
            var vehicleFactRequest = request.decision() != null
                    && request.decision().toolFamily() == ToolFamily.VEHICLE_DETAILS;
            if (vehicleFactPrefetcher == null
                    || !vehicleFactRequest && !LocalReplayCatalogFactFollowUp.matches(request.message())) {
                return delegate.complete(request, context);
            }
            Optional<LocalReplayVehicleFactPrefetcher.PrefetchedVehicleFact> vehicleFact;
            try {
                vehicleFact = vehicleFactPrefetcher.prefetch(request.message(), buyerHistory);
            } catch (RuntimeException failure) {
                var topFrame = failure.getStackTrace().length == 0 ? "unknown"
                        : failure.getStackTrace()[0].getClassName() + "." + failure.getStackTrace()[0].getMethodName()
                        + ":" + failure.getStackTrace()[0].getLineNumber();
                log.error("Local replay prefetch failed: stage=vehicle_fact exceptionType={} topFrame={}",
                        failure.getClass().getSimpleName(), topFrame);
                throw failure;
            }
            if (vehicleFact.isEmpty()) return delegate.complete(request, context);
            var fact = vehicleFact.orElseThrow();
            var serialized = serialize(fact);
            if (serialized.length() > MAX_EVIDENCE_JSON_CHARS) return delegate.complete(request, context);
            recordPrecisionEvidence(request, "get_vehicle", fact.evidenceCount(), serialized);
            var enhanced = withMemoryContext(request,
                    appendEvidence(request.memoryContext(), serialized, VEHICLE_EVIDENCE_MARKER, VEHICLE_EVIDENCE_END));
            tracePrefetch(request, context, "replay_prefetch_vehicle_evidence", "get_vehicle", fact.evidenceCount());
            return delegate.complete(enhanced, context);
        }

        var comparison = prefetchResult.comparison();
        var evidence = serialize(comparison);
        if (evidence.length() > MAX_EVIDENCE_JSON_CHARS) {
            if (precisionEvidence != null) precisionEvidence.recordComparisonStatus(request.conversationId(),
                    LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.EVIDENCE_TOO_LARGE);
            return delegate.complete(request, context);
        }
        recordPrecisionEvidence(request, "compare_trims", comparison.evidenceCount(), evidence);
        var evidenceContext = appendEvidence(request.memoryContext(), evidence, EVIDENCE_MARKER, EVIDENCE_END);
        if (vehicleFactPrefetcher != null && asksForDimensions(request.message())) {
            var vehicleFacts = vehicleFactPrefetcher.prefetch(comparison.pair());
            if (!vehicleFacts.isEmpty()) {
                var serializedFacts = serialize(vehicleFacts);
                recordPrecisionEvidence(request, "get_vehicle", vehicleFacts.stream()
                        .mapToInt(LocalReplayVehicleFactPrefetcher.PrefetchedVehicleFact::evidenceCount).sum(),
                        serializedFacts);
                vehicleFacts.forEach(fact -> tracePrefetch(request, context,
                        "replay_prefetch_vehicle_pair_evidence", "get_vehicle", fact.evidenceCount()));
                evidenceContext = appendEvidence(evidenceContext, serializedFacts,
                        VEHICLE_EVIDENCE_MARKER, VEHICLE_EVIDENCE_END)
                        + "\n[REPLAY GUIDANCE] Missing fields are unknown, not proof of absence. "
                        + "If dimensions are not explicitly present in these records, say you cannot verify them "
                        + "from the retrieved evidence; do not claim the catalog has no dimensions.\n";
            }
        }
        var enhancedRequest = withMemoryContext(request, evidenceContext);
        tracePrefetch(request, context, "replay_prefetch_comparison", "compare_trims", comparison.evidenceCount());
        if (precisionEvidence != null) precisionEvidence.recordComparisonStatus(request.conversationId(),
                LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.INJECTED);
        var enhancedContext = context;
        if (context != null && snapshotSha256 != null) {
            // Hash retrieval failures must propagate; do not silently accept an unvalidated snapshot.
            var snapshot = snapshotSha256.get();
            try {
                var envelope = PrefetchedComparisonEvidence.liveComparison(enhancedRequest, snapshot,
                        comparison.pair().model(), comparison.pair().year(), comparison.pair().trims(),
                        objectMapper.writeValueAsString(comparison.comparison()));
                enhancedContext = context.withPrefetchedComparison(envelope);
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("comparison evidence could not be serialized", failure);
            } catch (IllegalArgumentException invalidEvidence) {
                log.warn("Local replay comparison envelope rejected; adapter safety requirements remain active");
            }
        }
        return delegate.complete(enhancedRequest, enhancedContext);
    }

    List<LocalQwenTranscriptWriter.ToolTrace> seedToolTracesForTurn(int turnNumber, String buyerMessage) {
        if (delegate instanceof LocalQwenSeededChatCompletionPort seeded) {
            return seeded.seedToolTracesForTurn(turnNumber, buyerMessage);
        }
        return List.of();
    }

    private synchronized String rememberBuyerMessageAndGetPriorContext(AssistantRequest request) {
        var messages = buyerMessagesByConversation.computeIfAbsent(request.conversationId(),
                ignored -> new ArrayDeque<>());
        var priorContext = renderBuyerHistory(messages);
        if (request.message() != null && !request.message().isBlank()
                && (messages.isEmpty() || !Objects.equals(messages.peekLast(), request.message()))) {
            messages.addLast(request.message());
            while (messages.size() > MAX_REPLAY_BUYER_MESSAGES) messages.removeFirst();
        }
        return priorContext;
    }

    private synchronized LocalComparisonPairResolver.Pair latestExactBuyerPair(UUID conversationId) {
        return latestExactBuyerPairByConversation.get(conversationId);
    }

    private static String renderBuyerHistory(ArrayDeque<String> messages) {
        var history = new StringBuilder("[HISTORIAL DE CONVERSACIÓN]\n");
        for (var message : messages) history.append("- usuario: ").append(message).append('\n');
        return history.append("[FIN DEL HISTORIAL]").toString();
    }

    private String serialize(LocalReplayVehicleFactPrefetcher.PrefetchedVehicleFact fact) {
        try {
            return objectMapper.writeValueAsString(fact);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay vehicle evidence could not be serialized", failure);
        }
    }

    private String serialize(java.util.List<LocalReplayVehicleFactPrefetcher.PrefetchedVehicleFact> facts) {
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay vehicle pair evidence could not be serialized", failure);
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

    private void recordPrecisionEvidence(AssistantRequest request, String toolName, int evidenceCount,
                                         String serializedEvidence) {
        if (precisionEvidence == null) return;
        try {
            var values = new StringBuilder();
            appendScalarEvidence(values, objectMapper.readTree(serializedEvidence));
            precisionEvidence.recordToolEvidence(request.conversationId(), toolName, evidenceCount, values.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("local replay precision evidence could not be read", failure);
        }
    }

    private static void appendScalarEvidence(StringBuilder target, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isValueNode()) {
            if (!node.isBoolean()) target.append(node.asText()).append(' ');
            return;
        }
        if (node.isArray()) node.forEach(child -> appendScalarEvidence(target, child));
        else node.fields().forEachRemaining(entry -> appendScalarEvidence(target, entry.getValue()));
    }

    private static String appendEvidence(String existingContext, String evidence, String marker, String end) {
        var prefix = existingContext == null || existingContext.isBlank() ? "" : existingContext.strip() + "\n\n";
        return prefix + marker + "\n" + evidence + "\n" + end;
    }

    private static String safeFingerprint(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean asksForDimensions(String message) {
        return message != null && java.text.Normalizer.normalize(message, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT).contains("dimension");
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
