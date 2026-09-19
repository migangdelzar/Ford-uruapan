package com.ford.service.channels.application;

import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.channels.application.port.out.ChannelBindingRepository;
import com.ford.service.channels.domain.Channel;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ChannelBindingService {

    private final ChannelBindingRepository repository;

    public ChannelBindingService(ChannelBindingRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public ChannelBinding resolve(String webhookKey, Channel requestedChannel) {
        if (webhookKey == null || webhookKey.isBlank() || requestedChannel == null) {
            throw new SecurityException("Invalid channel binding");
        }
        var binding = repository.findEnabledByWebhookKey(webhookKey)
                .filter(ChannelBinding::enabled)
                .filter(candidate -> candidate.channel() == requestedChannel)
                .orElseThrow(() -> new SecurityException("Invalid channel binding"));
        if (binding.tenantId() == null || binding.providerAccountId() == null
                || binding.providerAccountId().isBlank()
                || binding.secretReference() == null || binding.secretReference().isBlank()) {
            throw new SecurityException("Invalid channel binding");
        }
        return binding;
    }
}
