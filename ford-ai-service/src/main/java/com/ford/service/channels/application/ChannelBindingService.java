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

    public ChannelBinding resolve(Channel requestedChannel, String providerAccountId) {
        if (providerAccountId == null || providerAccountId.isBlank() || requestedChannel == null) {
            throw new SecurityException("Invalid channel binding");
        }
        var binding = repository.findEnabledByProviderAccount(requestedChannel, providerAccountId)
                .filter(ChannelBinding::enabled)
                .filter(candidate -> candidate.channel() == requestedChannel)
                .orElseThrow(() -> new SecurityException("Invalid channel binding"));
        if (binding.tenantId() == null || binding.providerAccountId() == null
                || binding.providerAccountId().isBlank()
                || !binding.providerAccountId().equals(providerAccountId)) {
            throw new SecurityException("Invalid channel binding");
        }
        return binding;
    }
}
