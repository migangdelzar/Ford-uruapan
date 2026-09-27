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

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@NamedInterface
public class InboundContentResolutionService {
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

        var image = inbound.kind() == InboundMessageKind.IMAGE;
        var candidates = providerPolicy.candidates(new AiExecutionContext(tenantId, false, conversationId),
                new ModelRequirements(false, false, image, !image));
        var provider = candidates.stream()
                .filter(candidate -> properties.allowsMediaProvider(candidate.id(), image, !image))
                .findFirst().orElse(null);
        if (provider == null) return clarification();

        try (var media = retrieve(tenantId, inbound.attachments().getFirst())) {
            if (media == null || media.sizeBytes() < 1 || media.sizeBytes() > properties.mediaMaxBytes()
                    || !matchesKind(inbound.kind(), media.mimeType())) return clarification();
            var interpretation = interpretWithinDeadline(provider.id(), inbound, media);
            if (interpretation == null || interpretation.isBlank()) return clarification();
            var content = safe(inbound.text());
            var resolved = content.isBlank() ? interpretation.strip()
                    : content + "\n\n" + interpretation.strip();
            return new ResolvedInboundContent(resolved, InboundContentStatus.READY);
        } catch (RuntimeException exception) {
            return clarification();
        }
    }

    private String interpretWithinDeadline(String providerId, AcceptedInboundMessage inbound,
                                           com.ford.service.channels.domain.RetrievedMedia media) {
        var task = new FutureTask<>(() -> interpreter.interpret(providerId, inbound.kind(), media.resource(),
                media.mimeType(), safe(inbound.text())));
        try {
            mediaTaskExecutor.execute(task);
            return task.get(properties.mediaTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            return null;
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException exception) {
            return null;
        }
    }

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
