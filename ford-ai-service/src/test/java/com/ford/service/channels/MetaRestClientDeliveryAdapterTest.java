package com.ford.service.channels;

import com.ford.service.channels.adapter.out.meta.MetaRestClientDeliveryAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.OutboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class MetaRestClientDeliveryAdapterTest {

    @Test
    void sendsConfiguredMetaAccessTokenOnOutboundDelivery() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.example/messages"))
                .andExpect(header("Authorization", "Bearer meta-access-token"))
                .andExpect(content().json("""
                        {
                          "messaging_product": "whatsapp",
                          "to": "recipient-1",
                          "type": "text",
                          "text": {"body": "Hola"}
                        }
                        """))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.1\"}]}", MediaType.APPLICATION_JSON));

        var adapter = new MetaRestClientDeliveryAdapter(
                builder.build(), URI.create("https://graph.example/messages"), "meta-access-token", Channel.WHATSAPP);

        var result = adapter.send(new OutboundMessage(
                UUID.randomUUID(), Channel.WHATSAPP, "recipient-1", "Hola", "idempotency-1"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("wamid.1");
        server.verify();
    }

    @Test
    void sendsMessengerTextThroughItsConfiguredPageEndpoint() {
        assertMetaTextDelivery(Channel.MESSENGER, "https://graph.example/page/messages", "mid.1",
                "{\"recipient\":{\"id\":\"recipient-1\"},\"message\":{\"text\":\"Hola\"}}");
    }

    @Test
    void sendsInstagramTextThroughItsConfiguredProfessionalAccountEndpoint() {
        assertMetaTextDelivery(Channel.INSTAGRAM, "https://graph.example/ig/messages", "mid.2",
                "{\"recipient\":{\"id\":\"recipient-1\"},\"message\":{\"text\":\"Hola\"}}");
    }

    @Test
    void preservesPermanentClientErrorsAndTransientRateLimits() {
        var clientErrorBuilder = RestClient.builder();
        var clientErrorServer = MockRestServiceServer.bindTo(clientErrorBuilder).build();
        clientErrorServer.expect(requestTo("https://graph.example/client-error"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        var clientErrorAdapter = new MetaRestClientDeliveryAdapter(clientErrorBuilder.build(),
                URI.create("https://graph.example/client-error"), "token", Channel.MESSENGER);
        var clientError = clientErrorAdapter.send(new OutboundMessage(
                UUID.randomUUID(), Channel.MESSENGER, "recipient", "Hola", "key"));

        var throttleBuilder = RestClient.builder();
        var throttleServer = MockRestServiceServer.bindTo(throttleBuilder).build();
        throttleServer.expect(requestTo("https://graph.example/throttle"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var throttleAdapter = new MetaRestClientDeliveryAdapter(throttleBuilder.build(),
                URI.create("https://graph.example/throttle"), "token", Channel.INSTAGRAM);
        var throttled = throttleAdapter.send(new OutboundMessage(
                UUID.randomUUID(), Channel.INSTAGRAM, "recipient", "Hola", "key"));

        assertThat(clientError.accepted()).isFalse();
        assertThat(clientError.permanent()).isTrue();
        assertThat(throttled.accepted()).isFalse();
        assertThat(throttled.permanent()).isFalse();
        clientErrorServer.verify();
        throttleServer.verify();
    }

    @Test
    void includesStructuredMetaErrorDetailsButRedactsUnapprovedResponseFields() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.example/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"message":"Invalid parameter for recipient-1","type":"OAuthException",
                                 "code":100,"error_subcode":33,"fbtrace_id":"fb-trace-123"},
                                 "debug_token":"must-not-be-logged"}
                                """));
        var adapter = new MetaRestClientDeliveryAdapter(builder.build(),
                URI.create("https://graph.example/messages"), "meta-access-token", Channel.WHATSAPP);

        var result = adapter.send(new OutboundMessage(
                UUID.randomUUID(), Channel.WHATSAPP, "recipient-1", "customer message", "key"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.permanent()).isTrue();
        assertThat(result.error()).contains("HTTP 400", "OAuthException", "100", "33", "fb-trace-123");
        assertThat(result.error()).doesNotContain("recipient-1", "must-not-be-logged", "meta-access-token", "customer message");
        assertThat(result.error()).doesNotContain("Invalid parameter");
        server.verify();
    }

    private static void assertMetaTextDelivery(Channel channel, String endpoint, String messageId, String body) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endpoint))
                .andExpect(header("Authorization", "Bearer channel-token"))
                .andExpect(content().json(body))
                .andRespond(withSuccess("{\"message_id\":\"" + messageId + "\"}", MediaType.APPLICATION_JSON));
        var adapter = new MetaRestClientDeliveryAdapter(builder.build(), URI.create(endpoint), "channel-token", channel);

        var result = adapter.send(new OutboundMessage(UUID.randomUUID(), channel,
                "recipient-1", "Hola", "stable-idempotency-key"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo(messageId);
        server.verify();
    }
}
