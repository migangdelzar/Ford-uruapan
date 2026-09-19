package com.ford.service.channels;

import com.ford.service.channels.adapter.out.meta.MetaRestClientDeliveryAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.OutboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
}
