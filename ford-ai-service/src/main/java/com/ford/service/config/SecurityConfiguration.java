package com.ford.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**").disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/webhooks/meta/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/operator/events/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/operator/ai/**").hasRole("OPERATOR")
                        .requestMatchers("/api/operator/**").hasRole("OPERATOR")
                        .anyRequest().permitAll())
                .httpBasic(httpBasic -> {});
        return http.build();
    }
}
