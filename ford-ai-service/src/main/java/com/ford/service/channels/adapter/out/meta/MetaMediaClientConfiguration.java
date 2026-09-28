package com.ford.service.channels.adapter.out.meta;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class MetaMediaClientConfiguration {
    @Bean
    RestClient metaMediaRestClient(RestClient.Builder builder) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(2500));
        return builder.clone().requestFactory(requestFactory).build();
    }
}
