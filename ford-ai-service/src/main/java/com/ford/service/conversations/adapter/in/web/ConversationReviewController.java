package com.ford.service.conversations.adapter.in.web;

import com.ford.service.conversations.application.ConversationReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversation-reviews")
public class ConversationReviewController {
    private final ConversationReviewService reviews;

    public ConversationReviewController(ConversationReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public ReviewPage list(@RequestParam(required = false) UUID tenantId,
                           @RequestParam(defaultValue = "PENDING") String status,
                           @RequestParam(defaultValue = "25") int limit,
                           Authentication authentication) {
        var claims = claims(authentication);
        var selectedTenant = resolveTenant(claims.tenantIds(), tenantId);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        var items = reviews.list(selectedTenant, status, limit);
        return new ReviewPage(items, items.size());
    }

    @PostMapping("/{reviewId}/resolve")
    @PreAuthorize("hasAuthority('SCOPE_operator:write') || hasRole('ADMIN')")
    public ResponseEntity<Void> resolve(@PathVariable UUID reviewId,
                                        @RequestParam(required = false) UUID tenantId,
                                        Authentication authentication) {
        var claims = claims(authentication);
        reviews.resolve(resolveTenant(claims.tenantIds(), tenantId), reviewId, claims.operatorId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversationId}/turns/{receiptId}/trace")
    @PreAuthorize("hasAuthority('SCOPE_operator:read') || hasRole('ADMIN')")
    public DecisionTracePage trace(@PathVariable UUID conversationId,
                                   @PathVariable UUID receiptId,
                                   @RequestParam(required = false) UUID tenantId,
                                   Authentication authentication) {
        var claims = claims(authentication);
        var events = reviews.trace(resolveTenant(claims.tenantIds(), tenantId), conversationId, receiptId);
        return new DecisionTracePage(events, events.size());
    }

    private static UUID resolveTenant(Set<UUID> tenantIds, UUID requested) {
        if (requested != null) {
            if (!tenantIds.contains(requested)) throw new SecurityException("operator is not authorized for tenant");
            return requested;
        }
        if (tenantIds.size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return tenantIds.iterator().next();
    }

    private static OperatorClaims claims(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("authenticated operator is required");
        }
        var tenantIds = new LinkedHashSet<UUID>();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            addTenantClaims(tenantIds, jwt.getClaims().get("tenant_ids"));
            addTenantClaims(tenantIds, jwt.getClaims().get("tenant_id"));
        } else {
            authentication.getAuthorities().stream().map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .filter(authority -> authority.startsWith("TENANT_"))
                    .map(authority -> UUID.fromString(authority.substring("TENANT_".length())))
                    .forEach(tenantIds::add);
        }
        if (tenantIds.isEmpty()) throw new SecurityException("tenant claim is required");
        UUID operatorId;
        try {
            operatorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalidUuid) {
            operatorId = UUID.nameUUIDFromBytes(authentication.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new OperatorClaims(operatorId, Set.copyOf(tenantIds));
    }

    private static void addTenantClaims(Set<UUID> target, Object claim) {
        if (claim instanceof Iterable<?> values) {
            for (var value : values) target.add(UUID.fromString(value.toString()));
        } else if (claim instanceof String text && !text.isBlank()) {
            for (var value : text.split(",")) target.add(UUID.fromString(value.trim()));
        }
    }

    private record OperatorClaims(UUID operatorId, Set<UUID> tenantIds) { }

    public record ReviewPage(List<ConversationReviewService.ReviewItem> items, int count) {
        public ReviewPage { items = List.copyOf(items); }
    }

    public record DecisionTracePage(List<com.ford.service.ai.application.DecisionTraceEvent> events, int count) {
        public DecisionTracePage { events = List.copyOf(events); }
    }

}
