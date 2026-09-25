package com.ford.service.channels.application.port.out;

import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.channels.domain.Channel;

import java.util.Optional;

public interface ChannelBindingRepository {
    Optional<ChannelBinding> findEnabledByProviderAccount(Channel channel, String providerAccountId);
}
