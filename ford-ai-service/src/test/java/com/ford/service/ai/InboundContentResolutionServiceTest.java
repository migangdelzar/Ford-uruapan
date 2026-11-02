package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.application.InboundMediaInterpretationPort;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.MetaMediaRetrievalPort;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.channels.domain.RetrievedMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class InboundContentResolutionServiceTest {

    private static final String SAFE_CLARIFICATION =
            "No pude interpretar ese archivo con seguridad. ¿Podría describirme lo que necesita o enviarlo en un mensaje de texto?";

    @Test
    void returnsTextWithoutRetrievingMediaOrCallingAnInterpreter() {
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(new AiProperties(), List.of(), retrieval, interpreter);

        var result = service.resolve(message(InboundMessageKind.TEXT, "Hola", List.of()), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.READY);
        assertThat(result.text()).isEqualTo("Hola");
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void preservesInteractiveAndLocationContentAsReadyText() {
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(new AiProperties(), List.of(), retrieval, interpreter);

        var location = service.resolve(message(InboundMessageKind.LOCATION,
                "Ubicación compartida: 19.4, -102.1", List.of()), UUID.randomUUID(), UUID.randomUUID());
        var choice = service.resolve(message(InboundMessageKind.INTERACTIVE, "Opción elegida: Ranger",
                List.of()), UUID.randomUUID(), UUID.randomUUID());

        assertThat(location.status()).isEqualTo(InboundContentStatus.READY);
        assertThat(location.text()).contains("19.4", "-102.1");
        assertThat(choice.status()).isEqualTo(InboundContentStatus.READY);
        assertThat(choice.text()).contains("Ranger");
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void refusesMediaByDefaultWithoutCallingExternalSystems() {
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(new AiProperties(), capableProviders(), retrieval, interpreter);

        var result = service.resolve(image(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result).isEqualTo(new com.ford.service.ai.application.ResolvedInboundContent(
                SAFE_CLARIFICATION, InboundContentStatus.CLARIFICATION_REQUIRED));
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void interpretsApprovedImageAndAlwaysClosesRetrievedMedia(CapturedOutput output) {
        var properties = enabledImageProperties();
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var closed = new AtomicBoolean();
        var media = new RetrievedMedia(new ByteArrayResource(new byte[]{1, 2, 3}), "image/jpeg", 3,
                () -> closed.set(true));
        when(retrieval.retrieve(any(), eq("opaque-media-id"), anyLong())).thenReturn(media);
        when(interpreter.interpret(eq("approved-vision"), eq(InboundMessageKind.IMAGE), any(),
                eq("image/jpeg"), eq("Revise esta camioneta"))).thenReturn("La imagen muestra una SUV azul.");
        var service = service(properties, capableProviders(), retrieval, interpreter);

        var conversationId = UUID.randomUUID();
        var result = service.resolve(image(), UUID.randomUUID(), conversationId);

        assertThat(result.status()).isEqualTo(InboundContentStatus.READY);
        assertThat(result.text()).contains("Revise esta camioneta", "SUV azul");
        assertThat(closed).isTrue();
        assertThat(output.getOut()).contains("conversationId=" + conversationId, "kind=IMAGE", "provider=approved-vision")
                .doesNotContain("Revise esta camioneta", "SUV azul", "opaque-media-id", "https://");
    }

    @Test
    void transcribesApprovedAudioUsingAnAudioCapableProvider() {
        var properties = new AiProperties();
        properties.setMediaEnabled(true);
        properties.setMediaApprovedProviders(List.of("approved-vision"));
        properties.setMediaAudioProviders(List.of("approved-vision"));
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        when(retrieval.retrieve(any(), eq("audio-media-id"), anyLong())).thenReturn(
                new RetrievedMedia(new ByteArrayResource(new byte[]{1, 2}), "audio/ogg", 2, null));
        when(interpreter.interpret(eq("approved-vision"), eq(InboundMessageKind.AUDIO), any(),
                eq("audio/ogg"), eq(""))).thenReturn("Quiero una cita para probar una Ranger.");
        var service = service(properties, capableProviders(), retrieval, interpreter);
        var audio = message(InboundMessageKind.AUDIO, "",
                List.of(new InboundAttachment("audio-media-id", "audio/ogg", null, 2L)));

        var result = service.resolve(audio, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.READY);
        assertThat(result.text()).isEqualTo("Quiero una cita para probar una Ranger.");
    }

    @Test
    void returnsSafeClarificationForUnsupportedDocumentWithoutCallingMediaSystems() {
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(enabledImageProperties(), capableProviders(), retrieval, interpreter);
        var document = message(InboundMessageKind.DOCUMENT, "", List.of());

        var result = service.resolve(document, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void doesNotInterpretMediaWhenNoApprovedHealthyCapableProviderExists() {
        var properties = enabledImageProperties();
        properties.setMediaApprovedProviders(List.of());
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(properties, capableProviders(), retrieval, interpreter);

        var result = service.resolve(image(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void missingAttachmentReturnsClarificationWithoutRetrieval() {
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var service = service(enabledImageProperties(), capableProviders(), retrieval, interpreter);

        var result = service.resolve(message(InboundMessageKind.IMAGE, "", List.of()),
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        verifyNoInteractions(retrieval, interpreter);
    }

    @Test
    void providerFailureReturnsSafeClarificationAndClosesMedia(CapturedOutput output) {
        var properties = enabledImageProperties();
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var closed = new AtomicBoolean();
        when(retrieval.retrieve(any(), eq("opaque-media-id"), anyLong())).thenReturn(
                new RetrievedMedia(new ByteArrayResource(new byte[]{1}), "image/jpeg", 1,
                        () -> closed.set(true)));
        when(interpreter.interpret(anyString(), any(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("caption private customer content provider unavailable"));
        var service = service(properties, capableProviders(), retrieval, interpreter);

        var conversationId = UUID.randomUUID();
        var result = service.resolve(image(), UUID.randomUUID(), conversationId);

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        assertThat(closed).isTrue();
        assertThat(output.getOut()).contains("conversationId=" + conversationId, "exceptionType=ExecutionException")
                .doesNotContain("caption private customer content", "opaque-media-id", "test-token");
    }

    @Test
    void rejectsAndClosesMediaLargerThanConfiguredLimit() {
        var properties = enabledImageProperties();
        properties.setMediaMaxBytes(2);
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var closed = new AtomicBoolean();
        when(retrieval.retrieve(any(), eq("opaque-media-id"), eq(2L))).thenReturn(
                new RetrievedMedia(new ByteArrayResource(new byte[]{1, 2, 3}), "image/jpeg", 3,
                        () -> closed.set(true)));
        var service = service(properties, capableProviders(), retrieval, interpreter);

        var result = service.resolve(image(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        assertThat(closed).isTrue();
        verifyNoInteractions(interpreter);
    }

    @Test
    void timesOutInterpretationAndClosesMedia() {
        var properties = enabledImageProperties();
        properties.setMediaTimeout(Duration.ofMillis(1));
        var retrieval = mock(MetaMediaRetrievalPort.class);
        var interpreter = mock(InboundMediaInterpretationPort.class);
        var closed = new AtomicBoolean();
        var interpretationStarted = new CountDownLatch(1);
        when(retrieval.retrieve(any(), anyString(), anyLong())).thenReturn(
                new RetrievedMedia(new ByteArrayResource(new byte[]{1}), "image/jpeg", 1,
                        () -> closed.set(true)));
        when(interpreter.interpret(anyString(), any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            interpretationStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "descripción";
        });
        TaskExecutor asynchronousExecutor = task -> {
            var thread = new Thread(task, "test-media-interpreter");
            thread.setDaemon(true);
            thread.start();
            try {
                assertThat(interpretationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Timed out waiting for media interpretation to start", exception);
            }
        };
        var service = new InboundContentResolutionService(properties,
                new DefaultModelProviderPolicy(capableProviders()), retrieval, interpreter, asynchronousExecutor);

        var result = service.resolve(image(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(InboundContentStatus.CLARIFICATION_REQUIRED);
        assertThat(closed).isTrue();
    }

    private static InboundContentResolutionService service(AiProperties properties,
                                                            List<ProviderDescriptor> providers,
                                                            MetaMediaRetrievalPort retrieval,
                                                            InboundMediaInterpretationPort interpreter) {
        return new InboundContentResolutionService(properties, new DefaultModelProviderPolicy(providers),
                retrieval, interpreter, new SyncTaskExecutor());
    }

    private static List<ProviderDescriptor> capableProviders() {
        return List.of(new ProviderDescriptor("approved-vision",
                new ProviderCapabilities(true, true, true, true, true), true, true, "vision-v1"));
    }

    private static AiProperties enabledImageProperties() {
        var properties = new AiProperties();
        properties.setMediaEnabled(true);
        properties.setMediaApprovedProviders(List.of("approved-vision"));
        properties.setMediaImageProviders(List.of("approved-vision"));
        return properties;
    }

    private static AcceptedInboundMessage image() {
        return message(InboundMessageKind.IMAGE, "Revise esta camioneta",
                List.of(new InboundAttachment("opaque-media-id", "image/jpeg", "truck.jpg", 3L)));
    }

    private static AcceptedInboundMessage message(InboundMessageKind kind, String text,
                                                  List<InboundAttachment> attachments) {
        return new AcceptedInboundMessage("wamid.inbound", "customer", text, Instant.now(), kind, attachments);
    }
}
