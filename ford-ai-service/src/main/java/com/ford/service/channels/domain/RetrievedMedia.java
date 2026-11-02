package com.ford.service.channels.domain;

import org.springframework.core.io.Resource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Size-verified temporary media. Closing releases any backing temporary resource. */
public final class RetrievedMedia implements AutoCloseable {
    private final Resource resource;
    private final String mimeType;
    private final long sizeBytes;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RetrievedMedia(Resource resource, String mimeType, long sizeBytes, Runnable cleanup) {
        this.resource = Objects.requireNonNull(resource);
        this.mimeType = Objects.requireNonNull(mimeType);
        if (sizeBytes < 0) throw new IllegalArgumentException("media size cannot be negative");
        this.sizeBytes = sizeBytes;
        this.cleanup = cleanup == null ? () -> { } : cleanup;
    }

    public Resource resource() { return resource; }
    public String mimeType() { return mimeType; }
    public long sizeBytes() { return sizeBytes; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }
}
