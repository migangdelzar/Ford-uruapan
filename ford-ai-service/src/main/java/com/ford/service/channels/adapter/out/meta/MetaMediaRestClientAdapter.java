package com.ford.service.channels.adapter.out.meta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.api.MetaMediaRetrievalPort;
import com.ford.service.channels.domain.RetrievedMedia;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.List;
import java.util.Map;

@Component
public class MetaMediaRestClientAdapter implements MetaMediaRetrievalPort {
    private final RestClient restClient;
    private final JdbcClient jdbc;
    private final MetaMediaProperties properties;
    private final ObjectMapper objectMapper;

    public MetaMediaRestClientAdapter(@Qualifier("metaMediaRestClient") RestClient restClient, JdbcClient jdbc,
                                      MetaMediaProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.jdbc = jdbc;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RetrievedMedia retrieve(UUID tenantId, String providerMediaId, long maxBytes) {
        if (tenantId == null || providerMediaId == null || providerMediaId.isBlank() || maxBytes < 1) {
            throw new IllegalArgumentException("Valid tenant, provider media ID, and byte limit are required");
        }
        if (!properties.isConfigured()) throw new IllegalStateException("Meta media retrieval is not configured");
        if (!belongsToTenant(tenantId, providerMediaId)) {
            throw new IllegalArgumentException("Meta media ID is not attached to an accepted tenant message");
        }

        var metadataUri = UriComponentsBuilder.fromUriString(properties.getGraphApiBaseUrl())
                .pathSegment(properties.getGraphApiVersion(), providerMediaId).build().encode().toUri();
        var metadata = restClient.get().uri(metadataUri)
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .retrieve().body(MediaMetadata.class);
        if (metadata == null || metadata.url() == null || metadata.mimeType() == null) {
            throw new IllegalStateException("Meta media metadata is incomplete");
        }
        var mediaUri = URI.create(metadata.url());
        validateMetaMediaUri(mediaUri);
        if (metadata.fileSize() != null && metadata.fileSize() > maxBytes) {
            throw new IllegalArgumentException("Meta media exceeds configured byte limit");
        }
        var downloaded = restClient.get().uri(mediaUri)
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Meta media download failed");
                    }
                    var length = response.getHeaders().getContentLength();
                    if (length > maxBytes) throw new IllegalArgumentException("Meta media exceeds configured byte limit");
                    try (var input = response.getBody()) {
                        var content = StreamUtils.copyToByteArray(new LimitedInputStream(input, maxBytes + 1));
                        if (content.length > maxBytes) {
                            java.util.Arrays.fill(content, (byte) 0);
                            throw new IllegalArgumentException("Meta media exceeds configured byte limit");
                        }
                        return new DownloadedMedia(content, response.getHeaders().getContentType() == null
                                ? null : response.getHeaders().getContentType().toString());
                    } catch (IOException exception) {
                        throw new IllegalStateException("Meta media download failed", exception);
                    }
                });
        var bytes = downloaded.bytes();
        var mimeType = metadata.mimeType().strip().toLowerCase(java.util.Locale.ROOT);
        if (downloaded.mimeType() != null && !mimeType.equalsIgnoreCase(downloaded.mimeType())) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Meta media MIME type does not match metadata");
        }
        return new RetrievedMedia(new ByteArrayResource(bytes), mimeType, bytes.length,
                () -> java.util.Arrays.fill(bytes, (byte) 0));
    }

    private boolean belongsToTenant(UUID tenantId, String mediaId) {
        final String attachment;
        try {
            attachment = objectMapper.writeValueAsString(List.of(Map.of("providerMediaId", mediaId)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Meta media ID is invalid", exception);
        }
        return jdbc.sql("""
                select exists(select 1 from channel_message message
                    join channel_binding binding on binding.id = message.binding_id
                    where message.tenant_id = :tenantId and message.channel = 'WHATSAPP'
                      and binding.tenant_id = :tenantId and binding.enabled
                      and message.attachment_metadata @> cast(:attachment as jsonb))
                """)
                .param("tenantId", tenantId)
                .param("attachment", attachment)
                .query(Boolean.class).single();
    }

    private static void validateMetaMediaUri(URI uri) {
        var host = uri.getHost();
        var trustedHost = host != null && (host.equals("facebook.com") || host.endsWith(".facebook.com")
                || host.equals("fbsbx.com") || host.endsWith(".fbsbx.com")
                || host.equals("fbcdn.net") || host.endsWith(".fbcdn.net"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !trustedHost
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Meta returned an untrusted media URL");
        }
    }

    private record MediaMetadata(String url,
                                 @JsonProperty("mime_type") String mimeType,
                                 @JsonProperty("file_size") Long fileSize) { }

    private record DownloadedMedia(byte[] bytes, String mimeType) { }

    /** Stops response streams after one byte over the configured maximum. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;
        private LimitedInputStream(java.io.InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            var value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            var count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
