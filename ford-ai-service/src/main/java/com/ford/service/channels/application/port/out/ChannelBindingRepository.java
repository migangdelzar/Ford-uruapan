package com.ford.service.channels.application.port.out;

import com.ford.service.channels.api.ChannelBinding;

import java.util.Optional;

public interface ChannelBindingRepository {
    Optional<ChannelBinding> findEnabledByWebhookKey(String webhookKey);
}
