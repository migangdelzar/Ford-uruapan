package com.ford.service.identity.adapter.in.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.application.OperatorIdentityService;
import com.ford.service.identity.application.OperatorUserDetailsService;
import com.ford.service.identity.application.OperatorTokenClaimsFactory;
import com.ford.service.identity.application.IdentityCheckedOneTimeTokenService;
import com.ford.service.identity.application.OneTimeTokenRequestLimiter;
import com.ford.service.config.AuthenticationProperties;
import org.springframework.boot.ApplicationRunner;
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
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
    ApplicationRunner registerFordWebBffClient(
            RegisteredClientRepository clients,
            PasswordEncoder passwordEncoder,
            AuthenticationProperties properties) {
        return arguments -> {
            require(properties.bffClientSecret(), "FORD_BFF_OIDC_CLIENT_SECRET");
            require(properties.signingKeyPkcs8Base64(), "FORD_SECURITY_JWK_PRIVATE_KEY");
            require(properties.emailApiKey(), "FORD_RESEND_API_KEY");
            require(properties.emailFrom(), "RESEND_FROM");

            var existing = clients.findByClientId(properties.bffClientId());
            if (existing != null) {
                if (!existing.getRedirectUris().contains(properties.bffRedirectUri())) {
                    throw new IllegalStateException("Registered Ford-web callback URI does not match configured URI");
                }
                return;
            }

            var client = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(properties.bffClientId())
                    .clientSecret(passwordEncoder.encode(properties.bffClientSecret()))
                    .clientName("Ford CRM BFF")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri(properties.bffRedirectUri())
                    .scopes(scopes -> scopes.addAll(OperatorIdentity.ALLOWED_SCOPES))
                    .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(Duration.ofMinutes(10))
                            .refreshTokenTimeToLive(Duration.ofHours(8))
                            .reuseRefreshTokens(false)
                            .build())
                    .build();
            clients.save(client);
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
            DaoAuthenticationProvider operatorPasswordAuthenticationProvider) throws Exception {
        http.securityMatcher("/login/**", "/logout", "/webauthn/**", "/api/auth/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository(properties)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/webauthn/register/**").authenticated()
                        .anyRequest().permitAll())
                .authenticationProvider(operatorPasswordAuthenticationProvider)
                .formLogin(withDefaults())
                .logout(logout -> logout.logoutSuccessHandler((request, response, authentication) ->
                        response.setStatus(org.springframework.http.HttpStatus.NO_CONTENT.value())))
                .webAuthn(webAuthn -> webAuthn
                        .rpId(properties.relyingPartyId())
                        .rpName(properties.relyingPartyName())
                        .allowedOrigins(properties.allowedOrigins()))
                .oneTimeTokenLogin(ott -> ott
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
    CsrfTokenRepository csrfTokenRepository(AuthenticationProperties properties) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(properties.csrfCookieSecure()).sameSite("Lax"));
        return repository;
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) throw new IllegalStateException(variable + " is required when Ford auth is enabled");
    }
}
