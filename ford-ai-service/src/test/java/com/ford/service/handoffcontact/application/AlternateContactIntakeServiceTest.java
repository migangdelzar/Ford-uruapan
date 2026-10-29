package com.ford.service.handoffcontact.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.api.StagingContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AlternateContactIntakeServiceTest {
    private static final String CONSENTED_TEXT = "Autorizo compartir mi correo con Ford: ana@example.com";

    @Test
    void disabledCaptureStillRedactsButDoesNotStageOrRevealContact() {
        var staging = mock(AlternateContactStagingPort.class);
        var service = service(staging, false, new AlternateContactCryptoProperties());
        var context = new StagingContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        service.captureAndStage(context, CONSENTED_TEXT, Instant.EPOCH);

        assertThat(service.redact(CONSENTED_TEXT)).doesNotContain("ana@example.com");
        assertThat(service.find(context, Instant.now())).isEmpty();
        verifyNoInteractions(staging);
    }

    @Test
    void enabledCaptureWithMissingDedicatedKeyFailsClosedWithoutPersisting() {
        var staging = mock(AlternateContactStagingPort.class);
        var service = service(staging, true, new AlternateContactCryptoProperties());
        var context = new StagingContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        service.captureAndStage(context, CONSENTED_TEXT, Instant.EPOCH);

        verifyNoInteractions(staging);
    }

    @Test
    void enabledFeatureWithoutDeletionJournalBucketFailsClosed() {
        var staging = mock(AlternateContactStagingPort.class);
        var journal = new AlternateContactDeletionJournalProperties();
        journal.setEnabled(true);
        journal.setNamespace("test-namespace");
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var cipher = new AlternateContactStagingCipher(new AlternateContactCryptoProperties(key, 1, null, 0),
                new ObjectMapper().findAndRegisterModules());
        var service = new AlternateContactIntakeService(new AlternateContactCapturePolicy(new AlternateContactRedactor()),
                cipher, staging, journal, true);
        var context = new StagingContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        service.captureAndStage(context, CONSENTED_TEXT, Instant.EPOCH);

        verifyNoInteractions(staging);
    }

    @Test
    void enabledCaptureStagesOnlyWhenDedicatedKeyExists() {
        var staging = mock(AlternateContactStagingPort.class);
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var service = service(staging, true, new AlternateContactCryptoProperties(key, 1, null, 0));
        var context = new StagingContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        service.captureAndStage(context, CONSENTED_TEXT, Instant.EPOCH);

        org.mockito.ArgumentCaptor<com.ford.service.handoffcontact.api.EncryptedContactEnvelope> envelope =
                org.mockito.ArgumentCaptor.forClass(com.ford.service.handoffcontact.api.EncryptedContactEnvelope.class);
        org.mockito.Mockito.verify(staging).save(org.mockito.ArgumentMatchers.eq(context.tenantId()),
                org.mockito.ArgumentMatchers.eq(context.conversationId()), org.mockito.ArgumentMatchers.eq(context.receiptId()),
                envelope.capture(), org.mockito.ArgumentMatchers.eq(Instant.EPOCH),
                org.mockito.ArgumentMatchers.eq(Instant.EPOCH.plusSeconds(24 * 60 * 60)));
        assertThat(envelope.getValue().ciphertext()).isNotEmpty();
    }

    private static AlternateContactIntakeService service(AlternateContactStagingPort staging, boolean enabled,
                                                          AlternateContactCryptoProperties properties) {
        var cipher = new AlternateContactStagingCipher(properties, new ObjectMapper().findAndRegisterModules());
        var journal = new AlternateContactDeletionJournalProperties();
        journal.setEnabled(enabled);
        journal.setNamespace("test-namespace");
        journal.setBucket("test-bucket");
        return new AlternateContactIntakeService(new AlternateContactCapturePolicy(new AlternateContactRedactor()),
                cipher, staging, journal, enabled);
    }
}
