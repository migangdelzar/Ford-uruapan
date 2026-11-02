package com.ford.service.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = OutboxConfigurationSliceTest.ProbeController.class)
@Import(OutboxConfiguration.class)
@TestPropertySource(properties = "ford.outbox.enabled=false")
class OutboxConfigurationSliceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void doesNotRegisterOutboxJobsWhenDisabled() {
        assertThat(context.getBeansOfType(OutboxDeliveryJob.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxRecoveryJob.class)).isEmpty();
    }

    @RestController
    static class ProbeController {
    }
}
