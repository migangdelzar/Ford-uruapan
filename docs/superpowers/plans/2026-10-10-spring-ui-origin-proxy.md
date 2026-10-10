# Spring UI Origin Proxy Implementation Plan

> Inline execution plan for the approved Ford CRM origin cutover.

**Goal:** Serve Ford-web Pages responses and Spring/BFF APIs under `https://ford.emme.business` without browser redirects to another hostname.

**Architecture:** Cloudflare DNS sends `ford.emme.business` to the existing emme-1 Tunnel. Tunnel ingress sends `/api/*` to the Ford-web BFF on port 3001 and auth/OIDC plus UI requests to Ford-service on port 8080. Spring MVC reverse proxies safe UI GET/HEAD requests to the independent `ford-web-ui` Pages hostname. Spring Security remains the owner of login, OIDC, OTP, and passkeys.

**Tech Stack:** Spring Boot 4.1 MVC `RestClient`, Spring Security, Cloudflare Pages, managed Cloudflare Tunnel and DNS.

## Tasks

1. Add proxy properties and Spring MVC UI proxy. Forward only GET/HEAD and a small request-header allowlist; return status, content headers, body, and rewritten Pages redirects. Exclude API/auth/OIDC/actuator/webhook routes. Add explicit UI route permissions and make GET `/login` use the UI while POST `/login` stays in Spring Security.
2. Add focused Spring tests for UI path proxying, query/header forwarding, upstream redirect rewriting, excluded paths, HEAD behavior, and local form-login processing ownership.
3. Enable framework forwarded-header handling and inject the UI upstream URL via deployment environment (`FORD_WEB_UI_UPSTREAM_URL`, defaulting to the Pages `pages.dev` origin).
4. Update the Ford-web architecture doc to describe the Pages upstream, same-origin emme-1 Tunnel, and route ownership.
5. Update the managed Tunnel ingress: `ford.emme.business /api/* → localhost:3001`, auth paths → `localhost:8080`, catch-all → `localhost:8080`; retain the current `ford-origin.emme.business` routes for rollback.
6. Build, verify, commit, push, deploy Spring. Smoke-check UI proxy, health, BFF API, OIDC discovery, and login page before DNS cutover.
7. Cut `ford.emme.business` DNS CNAME from Pages to the healthy Tunnel with proxy enabled. Verify public routes, then remove the Pages custom-domain association while keeping the `pages.dev` upstream available.

## Rollback

Restore `ford.emme.business` CNAME to `ford-crm-ui.pages.dev`, restore the Pages custom-domain association, and revert Tunnel ingress. The previous `ford-origin.emme.business` path remains available during rollout.

## Acceptance

- `ford.emme.business` stays the browser-visible origin for UI, BFF API, and auth flows.
- UI GET/HEAD responses come from the separate Pages deployment without a browser redirect to `pages.dev`.
- `/api/*` remains handled by the BFF; POST `/login`, OIDC, logout, and WebAuthn remain handled by Spring.
- Spring-generated absolute redirects use `https://ford.emme.business`.
- Unrelated local/untracked files remain untouched.
