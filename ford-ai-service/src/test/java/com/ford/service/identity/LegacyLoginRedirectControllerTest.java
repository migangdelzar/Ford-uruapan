package com.ford.service.identity;

import com.ford.service.identity.adapter.in.web.LegacyLoginRedirectController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyLoginRedirectControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LegacyLoginRedirectController())
            .build();

    @Test
    void redirectsLegacyBffLoginUrlToCanonicalLoginPage() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login"));
    }
}
