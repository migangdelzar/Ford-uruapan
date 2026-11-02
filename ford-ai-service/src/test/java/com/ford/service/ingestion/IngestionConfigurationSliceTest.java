package com.ford.service.ingestion;

import com.ford.service.ingestion.adapter.in.scheduling.FordIngestionConfiguration;
import com.ford.service.ingestion.adapter.in.scheduling.FordSyncJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = IngestionConfigurationSliceTest.ProbeController.class)
@Import(FordIngestionConfiguration.class)
@TestPropertySource(properties = "ford.ingestion.enabled=false")
class IngestionConfigurationSliceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void doesNotRegisterIngestionJobWhenDisabled() {
        assertThat(context.getBeansOfType(FordSyncJob.class)).isEmpty();
    }

    @RestController
    static class ProbeController {
    }
}
