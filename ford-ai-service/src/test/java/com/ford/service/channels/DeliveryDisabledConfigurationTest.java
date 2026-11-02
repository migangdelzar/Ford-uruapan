package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.meta.MetaDeliveryConfiguration;
import com.ford.service.channels.adapter.out.meta.MetaRestClientDeliveryAdapter;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.outbox.OutboxConfiguration;
import com.ford.service.outbox.OutboxDeliveryRouter;
import com.ford.service.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryDisabledConfigurationTest {
    @Test
    void disablesMetaAndOutboxWithoutRequiringCredentialsOrCreatingDeliveryBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(MetaDeliveryConfiguration.class, OutboxConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "ford.channels.meta.delivery.enabled=false",
                        "ford.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxDeliveryRouter.class);
                    assertThat(context).doesNotHaveBean(OutboxPublisher.class);
                    assertThat(context).doesNotHaveBean(ChannelDeliveryPort.class);
                    assertThat(context).doesNotHaveBean(MetaRestClientDeliveryAdapter.class);
                });
    }
}
