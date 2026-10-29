package com.ford.service.events;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.events.adapter.in.web.EventController;
import com.ford.service.events.api.AuditEventPage;
import com.ford.service.events.application.AuditEventQueryService;
import com.ford.service.events.application.RealtimeEventBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class EventControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;
    @MockitoBean AuditEventQueryService audit;
    @MockitoBean RealtimeEventBroker broker;

    @Test
    void listsAuditEventsWithEventsAuthority() throws Exception {
        when(audit.list(any(), any(), any(Integer.class), any())).thenReturn(new AuditEventPage(List.of(), null, false));
        mockMvc.perform(get("/api/v1/events").with(jwt().jwt(token -> token.subject("operator-1")
                        .claim("tenant_id", TENANT.toString())).authorities(() -> "SCOPE_events:read")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAuditEventsWithoutEventsAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/events").with(jwt().jwt(token -> token.subject("operator-1")
                        .claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());
    }
}
