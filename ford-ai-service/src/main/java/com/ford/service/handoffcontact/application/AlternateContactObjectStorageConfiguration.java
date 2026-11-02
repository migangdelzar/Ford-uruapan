package com.ford.service.handoffcontact.application;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.Region;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "ford.alternate-contact.deletion-journal", name = "enabled", havingValue = "true")
public class AlternateContactObjectStorageConfiguration {
    @Bean(destroyMethod = "close")
    ObjectStorage alternateContactObjectStorage(AlternateContactDeletionJournalProperties properties) {
        var client = ObjectStorageClient.builder()
                .build(InstancePrincipalsAuthenticationDetailsProvider.builder().build());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            client.setRegion(Region.fromRegionId(properties.getRegion()));
        }
        return client;
    }
}
