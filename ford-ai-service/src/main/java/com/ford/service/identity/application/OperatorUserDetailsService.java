package com.ford.service.identity.application;

import com.ford.service.config.AuthenticationProperties;
import com.ford.service.identity.api.OperatorIdentity;
import com.ford.service.identity.api.OperatorIdentityPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class OperatorUserDetailsService implements UserDetailsService {

    private final OperatorIdentityService identities;
    private final String passwordLoginEmail;
    private final String passwordLoginHash;

    public OperatorUserDetailsService(
            OperatorIdentityService identities,
            AuthenticationProperties properties,
            PasswordEncoder passwordEncoder) {
        this.identities = identities;
        var configuredEmail = properties.passwordLoginEmail();
        var configuredPassword = properties.passwordLoginPassword();
        if (configuredEmail == null || configuredEmail.isBlank() || configuredPassword == null || configuredPassword.isBlank()) {
            this.passwordLoginEmail = "";
            this.passwordLoginHash = null;
        } else {
            this.passwordLoginEmail = OperatorIdentity.normalizeEmail(configuredEmail);
            this.passwordLoginHash = passwordEncoder.encode(configuredPassword);
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var identity = identities.findActiveByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Operator credentials are invalid"));
        var password = identity.email().equals(passwordLoginEmail) ? passwordLoginHash : null;
        return new OperatorDetails(identity, password);
    }

    private record OperatorDetails(OperatorIdentity identity, String password) implements OperatorIdentityPrincipal {
        @Override public String getUsername() { return identity.email(); }
        @Override public String getPassword() { return password; }
        @Override public java.util.Collection<SimpleGrantedAuthority> getAuthorities() {
            var roleAuthorities = identity.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var scopeAuthorities = identity.scopes().stream()
                    .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                    .toList();
            return java.util.stream.Stream.concat(roleAuthorities.stream(), scopeAuthorities.stream()).toList();
        }
        @Override public boolean isAccountNonExpired() { return true; }
        @Override public boolean isAccountNonLocked() { return true; }
        @Override public boolean isCredentialsNonExpired() { return true; }
        @Override public boolean isEnabled() { return identity.enabled(); }
    }
}
