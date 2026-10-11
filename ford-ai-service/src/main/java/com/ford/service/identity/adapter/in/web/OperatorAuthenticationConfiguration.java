package com.ford.service.identity.adapter.in.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.ford.service.identity.application.OperatorIdentityService;
import com.ford.service.identity.application.OperatorUserDetailsService;
import com.ford.service.identity.application.OperatorTokenClaimsFactory;
import com.ford.service.identity.application.IdentityCheckedOneTimeTokenService;
import com.ford.service.identity.application.OneTimeTokenRequestLimiter;
import com.ford.service.config.AuthenticationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@ConditionalOnProperty(prefix = "ford.security.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuthenticationProperties.class)
public class OperatorAuthenticationConfiguration {

    @Bean
    DaoAuthenticationProvider operatorPasswordAuthenticationProvider(
            OperatorUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    JdbcPublicKeyCredentialUserEntityRepository jdbcPublicKeyCredentialUserEntityRepository(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    JdbcUserCredentialRepository jdbcUserCredentialRepository(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }

    @Bean
    JdbcOneTimeTokenService jdbcOneTimeTokenService(JdbcOperations jdbc) {
        var service = new JdbcOneTimeTokenService(jdbc);
        service.setCleanupCron("0 */5 * * * *");
        return service;
    }

    @Bean
    OneTimeTokenService oneTimeTokenService(JdbcOneTimeTokenService jdbcTokens,
            OperatorIdentityService identities, OneTimeTokenRequestLimiter limiter, java.time.Clock clock) {
        return new IdentityCheckedOneTimeTokenService(jdbcTokens, identities, limiter, clock);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthenticationProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuerUri()).build();
    }

    @Bean
    RSAKey operatorSigningKey(AuthenticationProperties properties) {
        try {
            var encoded = java.util.Base64.getDecoder().decode(properties.signingKeyPkcs8Base64());
            var privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).keyID("ford-operator-rsa-1").build();
        } catch (Exception exception) {
            throw new IllegalStateException("FORD_SECURITY_JWK_PRIVATE_KEY must contain a base64 PKCS#8 RSA private key", exception);
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey key) {
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    JwtDecoder operatorJwtDecoder(RSAKey key, AuthenticationProperties properties) throws com.nimbusds.jose.JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.issuerUri());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", values -> values != null && values.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> operatorTokenCustomizer(
            OperatorIdentityService identities,
            OperatorTokenClaimsFactory claimsFactory,
            AuthenticationProperties properties) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) return;
            var identity = identities.findActiveByEmail(context.getPrincipal().getName())
                    .orElseThrow(() -> new IllegalStateException("Authenticated operator is no longer active"));
            context.getClaims().claims(claims -> claims.putAll(
                    claimsFactory.claims(identity, context.getAuthorizedScopes())));
            context.getClaims().audience(List.of(properties.audience()));
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = authorizationServer.getEndpointsMatcher();
        http.securityMatcher(endpoints)
                .with(authorizationServer, server -> server.oidc(withDefaults()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authorizationServerLoginEntryPoint()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(endpoints));
        return http.build();
    }

    LoginUrlAuthenticationEntryPoint authorizationServerLoginEntryPoint() {
        return new LoginUrlAuthenticationEntryPoint("/login");
    }

    @Bean
    @Order(2)
    SecurityFilterChain operatorLoginSecurityFilterChain(
            HttpSecurity http,
            AuthenticationProperties properties,
            OneTimeTokenService oneTimeTokenService,
            OneTimeTokenGenerationSuccessHandler tokenGenerationSuccessHandler,
            DaoAuthenticationProvider operatorPasswordAuthenticationProvider,
            CsrfTokenRepository csrfTokenRepository) throws Exception {
        http.securityMatcher("/login/**", "/logout", "/webauthn/**", "/api/auth/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/csrf", "/api/auth/one-time-token").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/webauthn/register/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/auth/me")))
                .authenticationProvider(operatorPasswordAuthenticationProvider)
                .formLogin(form -> form.loginPage("/login"))
                .logout(logout -> logout
                        .logoutRequestMatcher(new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/logout"),
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout")))
                        .deleteCookies("SESSION", "JSESSIONID", "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(org.springframework.http.HttpStatus.NO_CONTENT.value())))
                .webAuthn(webAuthn -> webAuthn
                        .rpId(properties.relyingPartyId())
                        .rpName(properties.relyingPartyName())
                        .allowedOrigins(properties.allowedOrigins()))
                .oneTimeTokenLogin(ott -> ott
                        .loginPage("/login")
                        .tokenService(oneTimeTokenService)
                        .tokenGeneratingUrl("/api/auth/one-time-token")
                        .generateRequestResolver(request -> {
                            var email = request.getParameter("email");
                            if (email == null || email.isBlank()) email = request.getParameter("username");
                            return new GenerateOneTimeTokenRequest(email, Duration.ofMinutes(5));
                        })
                        .tokenGenerationSuccessHandler(tokenGenerationSuccessHandler)
                        .loginProcessingUrl("/login/ott"));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain operatorApiSecurityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            OperatorUserDetailsService userDetailsService) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf
                        .spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(request -> {
                            var authorization = request.getHeader("Authorization");
                            return authorization != null && authorization.startsWith("Bearer ");
                        }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterAfter(new OperatorSessionRefreshFilter(userDetailsService), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(AuthenticationProperties properties) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(properties.csrfCookieSecure()).sameSite("Lax"));
        return repository;
    }

}
