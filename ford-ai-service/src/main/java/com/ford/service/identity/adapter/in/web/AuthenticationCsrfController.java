package com.ford.service.identity.adapter.in.web;

import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/csrf")
public class AuthenticationCsrfController {

    @GetMapping
    public Map<String, String> token(
            @RequestAttribute("org.springframework.security.web.csrf.DeferredCsrfToken")
            DeferredCsrfToken deferredToken) {
        var token = deferredToken.get();
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
