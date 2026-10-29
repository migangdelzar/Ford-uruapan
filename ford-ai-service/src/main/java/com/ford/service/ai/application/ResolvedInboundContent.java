package com.ford.service.ai.application;

@org.springframework.modulith.NamedInterface
public record ResolvedInboundContent(String text, InboundContentStatus status) {
}
