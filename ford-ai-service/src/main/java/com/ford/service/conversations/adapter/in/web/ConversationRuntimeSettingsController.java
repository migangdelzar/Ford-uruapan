package com.ford.service.conversations.adapter.in.web;

import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsService;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/runtime-settings")
@PreAuthorize("hasAuthority('SCOPE_bot:manage') || hasRole('ADMIN')")
public class ConversationRuntimeSettingsController {

    private final ConversationRuntimeSettingsService settings;

    public ConversationRuntimeSettingsController(ConversationRuntimeSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot> get(
            @RequestParam(required = false) UUID tenantId, Authentication authentication) {
        var operator = operatorForTenant(authentication, tenantId);
        var snapshot = settings.current(resolveTenant(operator, tenantId));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(snapshot.revision())).body(snapshot);
    }

    @PutMapping
    public ResponseEntity<com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot> update(
            @RequestParam(required = false) UUID tenantId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ConversationRuntimeSettings request,
            Authentication authentication) {
        var operator = operatorForTenant(authentication, tenantId);
        var actorId = UUID.nameUUIDFromBytes(operator.subject().getBytes(StandardCharsets.UTF_8));
        var snapshot = settings.update(resolveTenant(operator, tenantId), actorId, parseRevision(ifMatch), request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(snapshot.revision())).body(snapshot);
    }

    private static AuthenticatedOperator operatorForTenant(Authentication authentication, UUID tenantId) {
        var operator = AuthenticatedOperator.from(authentication);
        if (tenantId != null && !operator.canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        if (tenantId == null && operator.tenantIds().size() != 1) {
            throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
        }
        return operator;
    }

    private static UUID resolveTenant(AuthenticatedOperator operator, UUID tenantId) {
        return tenantId == null ? operator.tenantIds().iterator().next() : tenantId;
    }

    private static long parseRevision(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        var normalized = value.trim().replaceAll("^\\\"|\\\"$", "");
        if (normalized.startsWith("v")) normalized = normalized.substring(1);
        try {
            var revision = Long.parseLong(normalized);
            if (revision < 0) throw new NumberFormatException("negative revision");
            return revision;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a settings revision", exception);
        }
    }

    private static String etag(long revision) {
        return "\"v" + revision + "\"";
    }
}
