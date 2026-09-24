package com.ford.service.ai;

import com.ford.service.ai.adapter.in.web.SystemPromptController;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.SystemPromptSnapshot;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemPromptController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class SystemPromptControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SystemPromptService service;

    @Test
    void readsCurrentPromptForOperator() throws Exception {
        when(service.current()).thenReturn(snapshot("seed prompt", 0));

        mockMvc.perform(get("/api/v1/operator/ai/system-prompt")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("seed prompt"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void replacesPromptWithExpectedVersion() throws Exception {
        when(service.replace(eq("updated prompt"), eq(0L), eq("operator")))
                .thenReturn(snapshot("updated prompt", 1));

        mockMvc.perform(put("/api/v1/operator/ai/system-prompt")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{\"content\":\"updated prompt\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("updated prompt"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsAnonymousPromptAccess() throws Exception {
        mockMvc.perform(get("/api/v1/operator/ai/system-prompt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsVersionConflictWithoutDiscardingTheEditorsDraft() throws Exception {
        when(service.replace(eq("updated prompt"), eq(0L), eq("operator")))
                .thenThrow(new SystemPromptService.VersionConflictException(0));

        mockMvc.perform(put("/api/v1/operator/ai/system-prompt")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{\"content\":\"updated prompt\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }

    private static SystemPromptSnapshot snapshot(String content, long version) {
        return new SystemPromptSnapshot(content, version, "operator", Instant.parse("2026-09-23T00:00:00Z"));
    }
}
