package com.ford.service.identity.adapter.in.web;

import com.ford.service.identity.api.OperatorIdentityPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Refreshes stored browser-session authorities against the active operator registry on API calls. */
public final class OperatorSessionRefreshFilter extends OncePerRequestFilter {

    private final UserDetailsService userDetailsService;

    public OperatorSessionRefreshFilter(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OperatorIdentityPrincipal) {
            try {
                var principal = userDetailsService.loadUserByUsername(authentication.getName());
                var refreshed = UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities());
                refreshed.setDetails(authentication.getDetails());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(refreshed);
                SecurityContextHolder.setContext(context);
            } catch (UsernameNotFoundException exception) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.sendError(HttpStatus.UNAUTHORIZED.value());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
