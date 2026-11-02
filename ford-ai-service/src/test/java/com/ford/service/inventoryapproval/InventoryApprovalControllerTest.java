package com.ford.service.inventoryapproval;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.inventoryapproval.adapter.in.web.InventoryApprovalController;
import com.ford.service.inventoryapproval.application.InventoryApprovalService;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryApprovalController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class InventoryApprovalControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryApprovalService approvals;

    @Test
    void requiresReadScopeToList() throws Exception {
        mockMvc.perform(get("/api/v1/inventory-approvals")
                        .param("tenantId", TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(approvals);
    }

    @Test
    void readsTenantScopedPendingQueue() throws Exception {
        when(approvals.list(any(), eq(TENANT), eq(InventoryApprovalStatus.PENDING), eq(25))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/inventory-approvals")
                        .param("tenantId", TENANT.toString()).param("status", "PENDING").param("limit", "25")
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk());

        verify(approvals).list(any(), eq(TENANT), eq(InventoryApprovalStatus.PENDING), eq(25));
    }

    @Test
    void requiresWriteScopeToApproveAndDoesNotCallServiceWhenForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/inventory-approvals/{id}/approve", UUID.randomUUID())
                        .param("tenantId", TENANT.toString())
                        .contentType("application/json")
                        .content("""
                                {"expectedApprovalVersion":1,"expectedConversationVersion":4,
                                 "exactText":"El asesor confirmó disponibilidad."}
                                """)
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(approvals);
    }
}
