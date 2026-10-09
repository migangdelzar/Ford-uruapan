package com.ford.service.ai;

import com.ford.service.ai.application.ToolResultTracker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTrackerTest {

    @Test
    void removesTrackingParametersAndCollectsOnlyOfficialFordSourceUrls() {
        var tracker = new ToolResultTracker();
        var response = tracker.sanitizeSourceUrls("""
                {"sourceUrl":"https://www.forduruapan.mx/EXPEDITION/27/?utm_campaign=x#trim",
                 "other":"https://attacker.example/path"}
                """);

        assertThat(response).contains("https://www.forduruapan.mx/EXPEDITION/27/")
                .doesNotContain("utm_campaign", "#trim");
        assertThat(tracker.sourceUrls()).containsExactly("https://www.forduruapan.mx/EXPEDITION/27/");
    }
}
