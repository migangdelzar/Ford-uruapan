package com.ford.service.channels.adapter.out.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.outbox.OutboxDeliveryRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ford.channels.meta.delivery.enabled", havingValue = "true")
@EnableConfigurationProperties(MetaDeliveryProperties.class)
public class MetaDeliveryConfiguration {

    @Bean
    OutboxDeliveryRouter metaOutboxDeliveryRouter(ObjectMapper objectMapper,
                                                   MetaDeliveryProperties properties,
                                                   CustomerWindowPort customerWindows) {
        var ports = new EnumMap<Channel, ChannelDeliveryPort>(Channel.class);
        addPort(ports, Channel.WHATSAPP, properties.getWhatsappEndpoint(), properties.getWhatsappAccessToken(),
                properties.isDiagnosticStacktraces(), properties.isInteractiveMessagesEnabled());
        addPort(ports, Channel.MESSENGER, properties.getMessengerEndpoint(), properties.getMessengerAccessToken(),
                properties.isDiagnosticStacktraces(), false);
        addPort(ports, Channel.INSTAGRAM, properties.getInstagramEndpoint(), properties.getInstagramAccessToken(),
                properties.isDiagnosticStacktraces(), false);
        return new MetaOutboxDeliveryRouter(objectMapper, ports, customerWindows);
    }

    private static void addPort(Map<Channel, ChannelDeliveryPort> ports, Channel channel,
                                String endpoint, String accessToken, boolean diagnosticStacktraces,
                                boolean interactiveMessagesEnabled) {
        if (!endpoint.isBlank()) {
            if (accessToken.isBlank()) {
                throw new IllegalStateException("Meta access token is required for " + channel);
            }
            ports.put(channel, new MetaRestClientDeliveryAdapter(
                    RestClient.builder().build(), URI.create(endpoint), accessToken, channel,
                    new ObjectMapper(), diagnosticStacktraces, interactiveMessagesEnabled));
        }
    }
}
