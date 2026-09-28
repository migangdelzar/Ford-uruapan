package com.ford.service.channels.api;

import com.ford.service.channels.domain.RetrievedMedia;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

@NamedInterface
public interface MetaMediaRetrievalPort {
    RetrievedMedia retrieve(UUID tenantId, String providerMediaId, long maxBytes);
}
