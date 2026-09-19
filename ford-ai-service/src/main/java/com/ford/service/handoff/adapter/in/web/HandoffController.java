package com.ford.service.handoff.adapter.in.web;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/handoffs")
public class HandoffController {

    private final HandoffService service;

    public HandoffController(HandoffService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffTicket request(@RequestBody HandoffRequest request) {
        return service.request(request, principal());
    }

    @PostMapping("/{ticketId}/claim")
    public HandoffTicket claim(@PathVariable UUID ticketId, @RequestParam long expectedVersion) {
        return service.claim(ticketId, expectedVersion, principal());
    }

    @PostMapping("/conversations/{conversationId}/pause")
    public HandoffTicket pause(@PathVariable UUID conversationId, @RequestParam long expectedVersion) {
        return service.pauseBot(conversationId, expectedVersion, principal());
    }

    @PostMapping("/conversations/{conversationId}/resume")
    public HandoffTicket resume(@PathVariable UUID conversationId, @RequestParam long expectedVersion) {
        return service.resumeBot(conversationId, expectedVersion, principal());
    }

    @GetMapping
    public List<HandoffTicket> list() {
        return service.list(principal());
    }

    private static OperatorPrincipal principal() {
        return OperatorPrincipal.from(SecurityContextHolder.getContext().getAuthentication());
    }
}
