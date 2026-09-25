package com.ford.service.channels;

import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.channels.application.ChannelBindingService;
import com.ford.service.channels.application.port.out.ChannelBindingRepository;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelBindingServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ChannelBinding ENABLED_BINDING = new ChannelBinding(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), TENANT_ID,
            Channel.WHATSAPP, "meta-phone-1", "meta-secret-1", true);

    @Test
    void resolvesTenantOnlyFromAnEnabledProviderAccountForTheRequestedChannel() {
        var repository = mock(ChannelBindingRepository.class);
        when(repository.findEnabledByProviderAccount(Channel.WHATSAPP, "meta-phone-1"))
                .thenReturn(Optional.of(ENABLED_BINDING));

        var binding = new ChannelBindingService(repository).resolve(Channel.WHATSAPP, "meta-phone-1");

        assertThat(binding.tenantId()).isEqualTo(TENANT_ID);
        assertThat(binding).isEqualTo(ENABLED_BINDING);
    }

    @Test
    void rejectsUnknownOrDisabledProviderAccounts() {
        var repository = mock(ChannelBindingRepository.class);
        when(repository.findEnabledByProviderAccount(Channel.WHATSAPP, "unknown-phone"))
                .thenReturn(Optional.empty());
        when(repository.findEnabledByProviderAccount(Channel.WHATSAPP, "disabled-phone"))
                .thenReturn(Optional.of(new ChannelBinding(
                        ENABLED_BINDING.id(), TENANT_ID, Channel.WHATSAPP,
                        "meta-phone-1", "meta-secret-1", false)));
        var service = new ChannelBindingService(repository);

        assertThatThrownBy(() -> service.resolve(Channel.WHATSAPP, "unknown-phone"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.resolve(Channel.WHATSAPP, "disabled-phone"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAValidBindingWhenTheRouteChannelDoesNotMatch() {
        var repository = mock(ChannelBindingRepository.class);
        when(repository.findEnabledByProviderAccount(Channel.WHATSAPP, "meta-phone-1"))
                .thenReturn(Optional.of(ENABLED_BINDING));

        assertThatThrownBy(() -> new ChannelBindingService(repository)
                .resolve(Channel.MESSENGER, "meta-phone-1"))
                .isInstanceOf(SecurityException.class);
    }
}
