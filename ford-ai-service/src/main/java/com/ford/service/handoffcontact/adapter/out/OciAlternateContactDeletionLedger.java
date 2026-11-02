package com.ford.service.handoffcontact.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.handoffcontact.application.AlternateContactDeletionJournalProperties;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class OciAlternateContactDeletionLedger implements AlternateContactDeletionLedger {
    private final ObjectProvider<ObjectStorage> clients;
    private final AlternateContactDeletionJournalProperties properties;
    private final ObjectMapper mapper;

    public OciAlternateContactDeletionLedger(ObjectProvider<ObjectStorage> clients,
                                              AlternateContactDeletionJournalProperties properties,
                                              ObjectMapper mapper) {
        this.clients = clients;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void record(DeletionMarker marker) {
        Objects.requireNonNull(marker, "deletion marker is required");
        if (!properties.isEnabled() || blank(properties.getNamespace()) || blank(properties.getBucket())
                || blank(properties.getPrefix())) {
            throw new IllegalStateException("external contact deletion journal is not configured");
        }
        var client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("external contact deletion journal client is unavailable");
        final byte[] body;
        try {
            body = mapper.writeValueAsBytes(marker);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not encode contact deletion marker");
        }
        var objectName = properties.getPrefix().replaceAll("/+$", "") + "/" + marker.markerId() + ".json";
        try {
            client.putObject(PutObjectRequest.builder()
                    .namespaceName(properties.getNamespace())
                    .bucketName(properties.getBucket())
                    .objectName(objectName)
                    .contentType("application/json")
                    .contentLength((long) body.length)
                    .putObjectBody(new ByteArrayInputStream(body))
                    .build());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("could not persist external contact deletion marker");
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
