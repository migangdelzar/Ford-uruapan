package com.ford.service.inventoryapproval.adapter.in.web;

import com.ford.service.inventoryapproval.api.InventoryApprovalApproveRequest;
import com.ford.service.inventoryapproval.api.InventoryApprovalVerifyRequest;
import com.ford.service.inventoryapproval.api.InventoryApprovalView;
import com.ford.service.inventoryapproval.application.InventoryApprovalService;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.operator.application.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory-approvals")
public class InventoryApprovalController {
    private final InventoryApprovalService approvals;

    public InventoryApprovalController(InventoryApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<InventoryApprovalView> list(@RequestParam(required = false) UUID tenantId,
                                            @RequestParam(required = false) InventoryApprovalStatus status,
                                            @RequestParam(defaultValue = "50") int limit,
                                            Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return approvals.list(operator, resolveTenant(operator, tenantId), status, limit).stream()
                .map(InventoryApprovalView::from).toList();
    }

    @GetMapping("/{approvalId}")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public InventoryApprovalView get(@PathVariable UUID approvalId,
                                     @RequestParam(required = false) UUID tenantId,
                                     Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return InventoryApprovalView.from(approvals.get(operator, resolveTenant(operator, tenantId), approvalId));
    }

    @PostMapping("/{approvalId}/verify")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public InventoryApprovalView verify(@PathVariable UUID approvalId,
                                        @RequestParam(required = false) UUID tenantId,
                                        @Valid @RequestBody InventoryApprovalVerifyRequest request,
                                        Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return InventoryApprovalView.from(approvals.verify(operator, resolveTenant(operator, tenantId),
                approvalId, request.expectedVersion(), request.outcome(), request.verifiedCount()));
    }

    @PostMapping("/{approvalId}/approve")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public InventoryApprovalView approve(@PathVariable UUID approvalId,
                                         @RequestParam(required = false) UUID tenantId,
                                         @Valid @RequestBody InventoryApprovalApproveRequest request,
                                         Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return InventoryApprovalView.from(approvals.approve(operator, resolveTenant(operator, tenantId),
                approvalId, request.expectedApprovalVersion(), request.expectedConversationVersion(),
                request.exactText()));
    }

    @PostMapping("/{approvalId}/reject")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public InventoryApprovalView reject(@PathVariable UUID approvalId,
                                        @RequestParam(required = false) UUID tenantId,
                                        @RequestParam long expectedVersion,
                                        Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        return InventoryApprovalView.from(approvals.reject(operator, resolveTenant(operator, tenantId),
                approvalId, expectedVersion));
    }

    private static UUID resolveTenant(AuthenticatedOperator operator, UUID requestedTenant) {
        if (requestedTenant != null) {
            if (!operator.canAccess(requestedTenant)) {
                throw new SecurityException("operator is not authorized for tenant");
            }
            return requestedTenant;
        }
        if (operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator.tenantIds().iterator().next();
    }
}
