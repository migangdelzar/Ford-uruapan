package com.ford.service.conversations.application;

import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerIdentityBackfill {

    private static final Logger log = LoggerFactory.getLogger(CustomerIdentityBackfill.class);

    private final CustomerMemoryIdentityRepository repository;
    private final CustomerIdentityFingerprint fingerprint;
    private final CustomerMemoryProperties properties;

    public CustomerIdentityBackfill(CustomerMemoryIdentityRepository repository,
                                    CustomerIdentityFingerprint fingerprint,
                                    CustomerMemoryProperties properties) {
        this.repository = Objects.requireNonNull(repository);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.properties = Objects.requireNonNull(properties);
    }

    public BackfillResult run(int batchSize) {
        properties.validate();
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 5000");
        }
        UUID after = null;
        var scanned = 0;
        var linked = 0;
        var skipped = 0;
        while (true) {
            var batch = repository.nextConversationBatch(after, batchSize);
            if (batch.isEmpty()) break;
            UUID lastProcessed = null;
            for (var source : batch) {
                scanned++;
                lastProcessed = source.conversationId();
                try {
                    var versions = new java.util.ArrayList<CustomerMemoryIdentityRepository.VersionedFingerprint>();
                    if (properties.hmacKey() != null && !properties.hmacKey().isBlank()) {
                        if (properties.hmacKeyVersion() == null) {
                            throw new IllegalStateException("current HMAC key version must be configured");
                        }
                        versions.add(new CustomerMemoryIdentityRepository.VersionedFingerprint(
                                properties.hmacKeyVersion(), fingerprint.fingerprint(source.tenantId(),
                                source.channel(), source.participantId(), properties.hmacKeyVersion(),
                                properties.hmacKeyBytes())));
                    }
                    if (properties.previousHmacKey() != null && !properties.previousHmacKey().isBlank()) {
                        if (properties.previousHmacKeyVersion() == null) {
                            throw new IllegalStateException("previous HMAC key version must be configured");
                        }
                        versions.add(new CustomerMemoryIdentityRepository.VersionedFingerprint(
                                properties.previousHmacKeyVersion(), fingerprint.fingerprint(source.tenantId(),
                                source.channel(), source.participantId(), properties.previousHmacKeyVersion(),
                                properties.previousHmacKeyBytes())));
                    }
                    if (versions.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    if (repository.findIdentityByConversation(source.tenantId(), source.channel(), source.conversationId())
                            != null) {
                        skipped++;
                        continue;
                    }
                    var identity = repository.resolveOrCreateIdentity(source.tenantId(), source.channel(), versions);
                    repository.linkConversation(source.tenantId(), source.channel(), identity.id(), source.conversationId());
                    linked++;
                } catch (RuntimeException exception) {
                    skipped++;
                    log.warn("Customer identity backfill skipped conversationId={} exceptionType={}",
                            source.conversationId(), exception.getClass().getSimpleName());
                }
            }
            if (lastProcessed == null || lastProcessed.equals(after)) {
                throw new IllegalStateException("customer identity backfill page cursor did not advance");
            }
            after = lastProcessed;
            if (batch.size() < batchSize) break;
        }
        var result = new BackfillResult(scanned, linked, skipped);
        log.info("Customer identity backfill complete scanned={} linked={} skipped={}",
                result.scanned(), result.linked(), result.skipped());
        return result;
    }

    public record BackfillResult(int scanned, int linked, int skipped) { }
}
