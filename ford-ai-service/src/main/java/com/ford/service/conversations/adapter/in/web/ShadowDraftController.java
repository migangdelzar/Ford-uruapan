package com.ford.service.conversations.adapter.in.web;

import com.ford.service.conversations.api.ShadowDraftReviewRequest;
import com.ford.service.conversations.api.ShadowDraftView;
import com.ford.service.conversations.application.ShadowDraftService;
import com.ford.service.conversations.domain.ShadowDraft;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.ResponseEntity;
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

/**
 * Operator review surface for MÍA shadow drafts. Every action requires an
 * authorized operator; nothing here can send a message to a customer.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ShadowDraftController {

    private final ShadowDraftService shadowService;

    public ShadowDraftController(ShadowDraftService shadowService) {
        this.shadowService = shadowService;
    }

    @GetMapping("/{conversationId}/shadow-drafts")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public List<ShadowDraftView> list(@PathVariable UUID conversationId,
                                         @RequestParam(required = false) UUID tenantId,
                                         Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        return shadowService.list(operator, selectedTenant, conversationId).stream()
                .map(ShadowDraftController::view)
                .toList();
    }

    @PostMapping("/{conversationId}/shadow-drafts/{draftId}/review")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ResponseEntity<Void> review(@PathVariable UUID conversationId,
                                       @PathVariable UUID draftId,
                                       @RequestParam(required = false) UUID tenantId,
                                       @RequestBody ShadowDraftReviewRequest request,
                                       Authentication authentication) {
        var operator = AuthenticatedOperator.from(authentication);
        var selectedTenant = resolveTenant(operator, tenantId);
        shadowService.review(operator, selectedTenant, conversationId, draftId,
                request.decision(), request.note());
        return ResponseEntity.noContent().build();
    }

    private static ShadowDraftView view(ShadowDraft draft) {
        return new ShadowDraftView(draft.id(), draft.conversationId(), draft.createdAt(),
                draft.expiresAt(), draft.reviewStatus(), draft.text());
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
