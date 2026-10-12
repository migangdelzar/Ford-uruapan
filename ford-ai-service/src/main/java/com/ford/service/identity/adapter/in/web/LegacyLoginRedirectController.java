package com.ford.service.identity.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps the retired BFF login-start URL pointed at Spring's single login page. */
@Controller
public class LegacyLoginRedirectController {

    @GetMapping("/api/auth/login")
    public String redirectToCanonicalLogin() {
        return "redirect:/login";
    }
}
