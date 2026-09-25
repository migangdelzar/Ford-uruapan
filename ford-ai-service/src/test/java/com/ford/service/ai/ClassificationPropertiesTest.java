package com.ford.service.ai;

import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ClassificationPropertiesTest.ProbeController.class)
@Import(ClassificationPropertiesTest.PropertiesConfiguration.class)
@TestPropertySource(properties = {
        "ford.ai.classification.enabled=true",
        "ford.ai.classification.mode=active",
        "ford.ai.classification.provider=typesafe",
        "ford.ai.classification.timeout-ms=1500",
        "ford.ai.classification.min-confidence=0.70",
        "ford.ai.classification.fallback=rules",
        "ford.ai.classification.pii-policy=redact",
        "ford.ai.classification.intent-enabled=true",
        "ford.ai.classification.lead-enabled=true",
        "ford.ai.classification.handoff-enabled=true",
        "ford.ai.classification.tools-enabled=true",
        "ford.ai.classification.sensitive-data-enabled=true"
})
class ClassificationPropertiesTest {

    private final ClassificationProperties properties;

    @Autowired
    ClassificationPropertiesTest(ClassificationProperties properties) {
        this.properties = properties;
    }

    @Test
    void bindsClassifierConfigurationFromSpringEnvironment() {
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.mode()).isEqualTo("active");
        assertThat(properties.provider()).isEqualTo("typesafe");
        assertThat(properties.timeoutMs()).isEqualTo(1500);
        assertThat(properties.minConfidence()).isEqualTo(0.70);
        assertThat(properties.fallback()).isEqualTo("rules");
        assertThat(properties.piiPolicy()).isEqualTo("redact");
        assertThat(properties.allClassifiersEnabled()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClassificationProperties.class)
    static class PropertiesConfiguration {
    }

    @RestController
    static class ProbeController {
    }
}
