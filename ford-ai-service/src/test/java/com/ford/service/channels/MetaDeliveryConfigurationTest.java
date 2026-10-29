package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.meta.MetaDeliveryConfiguration;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.outbox.OutboxDeliveryRouter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MetaDeliveryConfigurationTest {

    @Test
    void configuresSharedRouterWhenChannelDeliveryAndWindowPortAreAvailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(MetaDeliveryConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(CustomerWindowPort.class, () -> mock(CustomerWindowPort.class))
                .withPropertyValues(
                        "ford.channels.meta.delivery.enabled=true",
                        "ford.channels.meta.delivery.whatsapp-endpoint=https://graph.example/wa/messages",
                        "ford.channels.meta.delivery.whatsapp-access-token=wa-token",
                        "ford.channels.meta.delivery.messenger-endpoint=https://graph.example/page/messages",
                        "ford.channels.meta.delivery.messenger-access-token=page-token",
                        "ford.channels.meta.delivery.instagram-endpoint=https://graph.example/ig/messages",
                        "ford.channels.meta.delivery.instagram-access-token=ig-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxDeliveryRouter.class);
                });
    }
}
