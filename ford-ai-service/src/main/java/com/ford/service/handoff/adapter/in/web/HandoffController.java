package com.ford.service.handoff.adapter.in.web;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/handoffs")
public final class HandoffController {

    private final HandoffService service;

    public HandoffController(HandoffService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffTicket request(@RequestBody HandoffRequest request) {
        return service.request(request);
    }

    @PostMapping("/{ticketId}/claim")
    public HandoffTicket claim(@PathVariable UUID ticketId, @RequestParam UUID operatorId) {
        return service.claim(ticketId, operatorId);
    }

    @PostMapping("/conversations/{conversationId}/pause")
    public HandoffTicket pause(@PathVariable UUID conversationId, @RequestParam UUID operatorId) {
        return service.pauseBot(conversationId, operatorId);
    }

    @PostMapping("/conversations/{conversationId}/resume")
    public HandoffTicket resume(@PathVariable UUID conversationId, @RequestParam UUID operatorId) {
        return service.resumeBot(conversationId, operatorId);
    }

    @GetMapping
    public List<HandoffTicket> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }
}
