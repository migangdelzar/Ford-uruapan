package com.ford.service.ai.application;

import com.ford.service.channels.domain.InboundMessageKind;
import org.springframework.core.io.Resource;

public interface InboundMediaInterpretationPort {
    String interpret(String providerId, InboundMessageKind kind, Resource media,
                    String verifiedMimeType, String caption);
}
