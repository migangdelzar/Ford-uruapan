package com.ford.service.testlab;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.testlab.adapter.in.web.TestLabController;
import com.ford.service.testlab.application.TestLabProperties;
import com.ford.service.testlab.application.TestLabService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TestLabController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class TestLabControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean TestLabProperties properties;
    @MockitoBean TestLabService service;

    @Test
    void requiresTestLabAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/test-lab/faults")
                        .with(jwt().jwt(token -> token.subject("operator-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsWhenTestLabIsDisabled() throws Exception {
        when(properties.enabled()).thenReturn(false);
        mockMvc.perform(get("/api/v1/test-lab/faults")
                        .with(jwt().jwt(token -> token.subject("operator-1"))
                                .authorities(() -> "SCOPE_testlab:run")))
                .andExpect(status().isConflict());
    }
}
