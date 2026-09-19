package com.ford.service.catalog.application;

import com.ford.service.catalog.application.port.out.VehicleCatalogRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    @ConditionalOnBean(VehicleCatalogRepository.class)
    VehicleCatalog vehicleCatalog(VehicleCatalogRepository repository) {
        return new DefaultVehicleCatalog(repository);
    }
}
