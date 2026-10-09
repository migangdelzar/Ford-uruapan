package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class OperatorUserDetailsService implements UserDetailsService {

    private final OperatorIdentityService identities;

    public OperatorUserDetailsService(OperatorIdentityService identities) {
        this.identities = identities;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var identity = identities.findActiveByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Operator credentials are invalid"));
        return new OperatorDetails(identity);
    }

    private record OperatorDetails(OperatorIdentity identity) implements UserDetails {
        @Override public String getUsername() { return identity.email(); }
        @Override public String getPassword() { return null; }
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
