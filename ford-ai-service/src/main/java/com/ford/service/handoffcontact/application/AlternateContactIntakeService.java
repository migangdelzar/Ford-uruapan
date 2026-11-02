package com.ford.service.handoffcontact.application;

import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.EncryptedContactEnvelope;
import com.ford.service.handoffcontact.api.StagingContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AlternateContactIntakeService implements AlternateContactIntakePort {
    private static final Duration STAGING_TTL = Duration.ofHours(24);

    private final AlternateContactCapturePolicy policy;
    private final AlternateContactStagingCipher cipher;
    private final AlternateContactStagingPort staging;
    private final boolean captureEnabled;

    @Autowired
    public AlternateContactIntakeService(AlternateContactCapturePolicy policy,
                                         AlternateContactStagingCipher cipher,
                                         AlternateContactStagingPort staging,
                                         AlternateContactDeletionJournalProperties journalProperties,
                                         @Value("${ford.alternate-contact.enabled:false}") boolean featureEnabled) {
        this(policy, cipher, staging, featureEnabled && journalProperties.isConfigured());
    }

    public AlternateContactIntakeService(AlternateContactCapturePolicy policy,
                                         AlternateContactStagingCipher cipher,
                                         AlternateContactStagingPort staging) {
        this(policy, cipher, staging, false);
    }

    public AlternateContactIntakeService(AlternateContactCapturePolicy policy,
                                         AlternateContactStagingCipher cipher,
                                         AlternateContactStagingPort staging,
                                         boolean captureEnabled) {
        this.policy = policy;
        this.cipher = cipher;
        this.staging = staging;
        this.captureEnabled = captureEnabled;
    }

    @Override
    public String redact(String text) {
        return policy.redact(text);
    }

    @Override
    @Transactional
    public void captureAndStage(StagingContext context, String rawText, Instant receivedAt) {
        if (!captureEnabled || context == null || rawText == null || receivedAt == null) return;
        var extraction = policy.extract(rawText, context.receiptId().toString(), receivedAt);
        if (extraction.acceptedValues().isEmpty()) return;
        try {
            EncryptedContactEnvelope envelope = cipher.encrypt(extraction.acceptedValues(), context);
            staging.save(context.tenantId(), context.conversationId(), context.receiptId(), envelope,
                    receivedAt, receivedAt.plus(STAGING_TTL));
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            // Missing or invalid key must fail closed for optional contact, not reject the inbound message.
        }
    }

    @Override
    @Transactional
    public List<AlternateContactValue> find(StagingContext context, Instant now) {
        if (!captureEnabled) return List.of();
        return staging.find(context.tenantId(), context.conversationId(), context.receiptId(), now)
                .map(envelope -> cipher.decrypt(envelope, context)).orElseGet(List::of);
    }

    @Override
    @Transactional
    public void delete(StagingContext context) {
        staging.delete(context.tenantId(), context.conversationId(), context.receiptId());
    }
}
