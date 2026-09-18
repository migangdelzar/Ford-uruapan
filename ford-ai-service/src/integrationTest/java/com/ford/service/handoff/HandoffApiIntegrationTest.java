package com.ford.service.handoff;

import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.adapter.in.web.HandoffController;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HandoffController.class)
@AutoConfigureMockMvc(addFilters = false)
class HandoffApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HandoffService service;

    @Test
    void createsOperatorInboxTicket() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticket = new HandoffTicket(
                UUID.randomUUID(), tenantId, conversationId, null, HandoffStatus.HUMAN_PENDING,
                null, "HOT_LEAD", 0, Instant.parse("2026-09-18T18:00:00Z"), null, null);
        when(service.request(any())).thenReturn(ticket);

        mockMvc.perform(post("/api/operator/handoffs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","conversationId":"%s","reason":"HOT_LEAD"}
                                """.formatted(tenantId, conversationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HUMAN_PENDING"))
                .andExpect(jsonPath("$.reason").value("HOT_LEAD"));
    }
}
