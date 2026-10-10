package com.ford.service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, AuthenticationProperties.class})
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            AuthenticationProperties authenticationProperties,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/webhooks/meta/**", "/api/v1/health", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .requestMatchers(uiRequestMatcher()).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        if (properties.jwt().enabled() || authenticationProperties.enabled()) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        }

        return http.build();
    }

    private static org.springframework.security.web.util.matcher.RequestMatcher uiRequestMatcher() {
        var excluded = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/.well-known/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/logout"),
                PathPatternRequestMatcher.withDefaults().matcher("/webauthn/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/actuator/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/webhooks/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/error"));
        var uiMethodAndPath = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/**"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, "/**"));
        return new org.springframework.security.web.util.matcher.AndRequestMatcher(
                uiMethodAndPath, new NegatedRequestMatcher(excluded));
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        var defaultAuthorities = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new LinkedHashSet<GrantedAuthority>();
            var converted = defaultAuthorities.convert(jwt);
            if (converted != null) {
                authorities.addAll(converted);
            }
            roles(jwt).forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            return authorities;
        });
        return converter;
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.security.jwt", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ford.security.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        var jwtProperties = properties.jwt();
        if (jwtProperties.issuerUri() == null || jwtProperties.issuerUri().isBlank()) {
            throw new IllegalStateException(
                    "FORD_SECURITY_ISSUER_URI is required when FORD_SECURITY_JWT_ENABLED=true");
        }
        if (jwtProperties.audience() == null || jwtProperties.audience().isBlank()) {
            throw new IllegalStateException(
                    "FORD_SECURITY_AUDIENCE is required when FORD_SECURITY_JWT_ENABLED=true");
        }

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(jwtProperties.issuerUri());
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(jwtProperties.issuerUri());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud",
                values -> values != null && values.contains(jwtProperties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    private static Collection<String> roles(Jwt jwt) {
        var values = new LinkedHashSet<String>();
        addClaimValues(values, jwt.getClaims().get("roles"));
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value)
                .toList();
    }

    private static void addClaimValues(Collection<String> target, Object rawValue) {
        if (rawValue instanceof Collection<?> collection) {
            collection.stream().map(Object::toString).forEach(target::add);
        } else if (rawValue instanceof String string && !string.isBlank()) {
            Arrays.stream(string.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(target::add);
        }
    }
}
