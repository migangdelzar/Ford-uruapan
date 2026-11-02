package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.meta.MetaMediaProperties;
import com.ford.service.channels.adapter.out.meta.MetaMediaRestClientAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaMediaRestClientAdapterTest {

    @Test
    void downloadsOnlyTenantOwnedOpaqueMediaAndReturnsMemoryBackedBoundedResource() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = configuredProperties();
        var jdbc = tenantOwnedMediaLookup(true);
        var tenantId = UUID.randomUUID();
        server.expect(requestTo("https://graph.facebook.com/v23.0/media-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"url":"https://lookaside.fbsbx.com/media-123","mime_type":"image/jpeg","file_size":3}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://lookaside.fbsbx.com/media-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_JPEG));
        var adapter = new MetaMediaRestClientAdapter(builder.build(), jdbc, properties, new ObjectMapper(), "test-token");

        var media = adapter.retrieve(tenantId, "media-123", 10);

        assertThat(media.sizeBytes()).isEqualTo(3);
        assertThat(media.mimeType()).isEqualTo("image/jpeg");
        assertThat(media.resource().getContentAsByteArray()).containsExactly(1, 2, 3);
        media.close();
        server.verify();
    }

    @Test
    void refusesMediaNotAttachedToAnAcceptedMessageForThatTenant() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new MetaMediaRestClientAdapter(builder.build(), tenantOwnedMediaLookup(false),
                configuredProperties(), new ObjectMapper(), "test-token");

        assertThatThrownBy(() -> adapter.retrieve(UUID.randomUUID(), "media-123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant message");
        server.verify();
    }

    @Test
    void refusesCallerByteLimitsAboveTheHardSafetyCap() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new MetaMediaRestClientAdapter(builder.build(), tenantOwnedMediaLookup(true),
                configuredProperties(), new ObjectMapper(), "test-token");

        assertThatThrownBy(() -> adapter.retrieve(UUID.randomUUID(), "media-123", 10L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsUntrustedMetaMediaUrlBeforeSendingTheBearerToken() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v23.0/media-123"))
                .andRespond(withSuccess("""
                        {"url":"https://attacker.example/steal","mime_type":"image/jpeg","file_size":3}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new MetaMediaRestClientAdapter(builder.build(), tenantOwnedMediaLookup(true),
                configuredProperties(), new ObjectMapper(), "test-token");

        assertThatThrownBy(() -> adapter.retrieve(UUID.randomUUID(), "media-123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untrusted media URL");
        server.verify();
    }

    @Test
    void rejectsProviderDeclaredOversizedMediaBeforeDownloadingIt() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v23.0/media-123"))
                .andRespond(withSuccess("""
                        {"url":"https://lookaside.fbsbx.com/media-123","mime_type":"image/jpeg","file_size":11}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new MetaMediaRestClientAdapter(builder.build(), tenantOwnedMediaLookup(true),
                configuredProperties(), new ObjectMapper(), "test-token");

        assertThatThrownBy(() -> adapter.retrieve(UUID.randomUUID(), "media-123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte limit");
        server.verify();
    }

    @Test
    void rejectsMimeTypeMismatchAndDoesNotReturnTheDownloadedBytes() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v23.0/media-123"))
                .andRespond(withSuccess("""
                        {"url":"https://lookaside.fbsbx.com/media-123","mime_type":"image/jpeg","file_size":3}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://lookaside.fbsbx.com/media-123"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        var adapter = new MetaMediaRestClientAdapter(builder.build(), tenantOwnedMediaLookup(true),
                configuredProperties(), new ObjectMapper(), "test-token");

        assertThatThrownBy(() -> adapter.retrieve(UUID.randomUUID(), "media-123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME type");
        server.verify();
    }

    private static MetaMediaProperties configuredProperties() {
        var properties = new MetaMediaProperties();
        properties.setGraphApiVersion("v23.0");
        return properties;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JdbcClient tenantOwnedMediaLookup(boolean owned) {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Boolean.class)).thenReturn(query);
        when(query.single()).thenReturn(owned);
        return jdbc;
    }
}
