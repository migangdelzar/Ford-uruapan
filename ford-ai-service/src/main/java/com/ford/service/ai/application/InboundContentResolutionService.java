package com.ford.service.ai.application;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.MetaMediaRetrievalPort;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.modulith.NamedInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@NamedInterface
public class InboundContentResolutionService {
    private static final Logger log = LoggerFactory.getLogger(InboundContentResolutionService.class);
    private static final String SAFE_CLARIFICATION =
            "No pude interpretar ese archivo con seguridad. ¿Podría describirme lo que necesita o enviarlo en un mensaje de texto?";

    private final AiProperties properties;
    private final ModelProviderPolicy providerPolicy;
    private final MetaMediaRetrievalPort retrieval;
    private final InboundMediaInterpretationPort interpreter;
    private final TaskExecutor mediaTaskExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public InboundContentResolutionService(AiProperties properties, ModelProviderPolicy providerPolicy,
                                           MetaMediaRetrievalPort retrieval,
                                           InboundMediaInterpretationPort interpreter,
                                           @Qualifier("inboundMediaTaskExecutor") TaskExecutor mediaTaskExecutor) {
        this.properties = properties;
        this.providerPolicy = providerPolicy;
        this.retrieval = retrieval;
        this.interpreter = interpreter;
        this.mediaTaskExecutor = mediaTaskExecutor;
    }

    public ResolvedInboundContent resolve(AcceptedInboundMessage inbound, UUID tenantId, UUID conversationId) {
        if (inbound.kind() == InboundMessageKind.TEXT || inbound.kind() == InboundMessageKind.LOCATION
                || inbound.kind() == InboundMessageKind.INTERACTIVE) {
            return ready(inbound.text());
        }
        if (inbound.kind() != InboundMessageKind.IMAGE && inbound.kind() != InboundMessageKind.AUDIO) {
            return clarification();
        }
        if (!properties.mediaEnabled() || inbound.attachments().isEmpty()) return clarification();

        var started = System.nanoTime();
        log.info("Inbound media resolution started: conversationId={} kind={} attachmentCount={}",
                conversationId, inbound.kind(), inbound.attachments().size());
        var image = inbound.kind() == InboundMessageKind.IMAGE;
        var candidates = providerPolicy.candidates(new AiExecutionContext(tenantId, false, conversationId),
                new ModelRequirements(false, false, image, !image));
        var provider = candidates.stream()
                .filter(candidate -> properties.allowsMediaProvider(candidate.id(), image, !image))
                .findFirst().orElse(null);
        if (provider == null) {
            log.warn("Inbound media resolution stopped: conversationId={} kind={} reason=no_approved_healthy_capable_provider",
                    conversationId, inbound.kind());
            return clarification();
        }

        var activeMedia = new AtomicReference<com.ford.service.channels.domain.RetrievedMedia>();
        var task = new FutureTask<>(() -> retrieveAndInterpret(provider.id(), inbound, tenantId, conversationId,
                activeMedia));
        try {
            mediaTaskExecutor.execute(task);
            var result = task.get(properties.mediaTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result.status() == InboundContentStatus.READY) {
                log.info("Inbound media resolution completed: conversationId={} kind={} provider={} outcome=ready resultChars={} durationMs={}",
                        conversationId, inbound.kind(), provider.id(), result.text().length(), elapsedMs(started));
            }
            return result;
        } catch (TimeoutException exception) {
            task.cancel(true);
            close(activeMedia.getAndSet(null));
            log.warn("Inbound media resolution timed out: conversationId={} kind={} timeoutMs={}",
                    conversationId, inbound.kind(), properties.mediaTimeout().toMillis());
            return clarification();
        } catch (InterruptedException exception) {
            task.cancel(true);
            close(activeMedia.getAndSet(null));
            Thread.currentThread().interrupt();
            log.warn("Inbound media resolution interrupted: conversationId={} kind={}",
                    conversationId, inbound.kind());
            return clarification();
        } catch (ExecutionException | RuntimeException exception) {
            log.warn("Inbound media resolution failed: conversationId={} kind={} provider={} exceptionType={}",
                    conversationId, inbound.kind(), provider.id(), exception.getClass().getSimpleName());
            return clarification();
        }
    }

    private ResolvedInboundContent retrieveAndInterpret(String providerId, AcceptedInboundMessage inbound,
                                                        UUID tenantId, UUID conversationId,
                                                        AtomicReference<com.ford.service.channels.domain.RetrievedMedia> activeMedia) {
        try (var media = retrieve(tenantId, inbound.attachments().getFirst())) {
            if (media != null) activeMedia.set(media);
            if (Thread.currentThread().isInterrupted()) return clarification();
            if (media == null || media.sizeBytes() < 1 || media.sizeBytes() > properties.mediaMaxBytes()
                    || !matchesKind(inbound.kind(), media.mimeType())) {
                log.warn("Inbound media resolution stopped: conversationId={} kind={} reason=invalid_size_or_mime",
                        conversationId, inbound.kind());
                return clarification();
            }
            var interpretation = interpreter.interpret(providerId, inbound.kind(), media.resource(),
                    media.mimeType(), safe(inbound.text()));
            if (interpretation == null || interpretation.isBlank()) return clarification();
            var content = safe(inbound.text());
            var resolved = content.isBlank() ? interpretation.strip()
                    : content + "\n\n" + interpretation.strip();
            return new ResolvedInboundContent(resolved, InboundContentStatus.READY);
        } finally {
            activeMedia.set(null);
        }
    }

    private static void close(com.ford.service.channels.domain.RetrievedMedia media) {
        if (media != null) media.close();
    }

    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000L; }

    private com.ford.service.channels.domain.RetrievedMedia retrieve(UUID tenantId, InboundAttachment attachment) {
        return retrieval.retrieve(tenantId, attachment.providerMediaId(), properties.mediaMaxBytes());
    }

    private static boolean matchesKind(InboundMessageKind kind, String mimeType) {
        return mimeType != null && (kind == InboundMessageKind.IMAGE
                ? mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                : mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("audio/"));
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
    private static ResolvedInboundContent ready(String text) {
        return new ResolvedInboundContent(safe(text), InboundContentStatus.READY);
    }
    private static ResolvedInboundContent clarification() {
        return new ResolvedInboundContent(SAFE_CLARIFICATION, InboundContentStatus.CLARIFICATION_REQUIRED);
    }
}
