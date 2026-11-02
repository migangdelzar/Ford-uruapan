# Frontend authentication contract

Ford-service owns operator authentication. Spring Security handles passkeys,
password login, and one-time-token (OTT) email recovery. Browser requests use a
same-origin Spring Security session stored in Redis. Spring Authorization
Server remains available for external clients that need bearer JWTs.

## Configuration

When first-party auth is enabled, configure these server-side values:

```text
FORD_SECURITY_AUTH_ENABLED=true
FORD_SECURITY_ISSUER_URI=https://ford.emme.business
FORD_SECURITY_AUDIENCE=ford-ai-service
FORD_SECURITY_JWK_PRIVATE_KEY=<base64-encoded PKCS#8 RSA private key>
FORD_SESSION_TIMEOUT=8h
FORD_RESEND_API_KEY=<Resend API key>
RESEND_FROM=<verified sender>
```

The provider metadata is at
`https://ford.emme.business/.well-known/openid-configuration`. Production
serves the UI and API under `ford.emme.business`. Spring proxies UI asset
requests to the static Pages deployment; browser auth and API requests stay on
the same host and go directly to Spring.

## Browser login

The browser signs in directly through Spring Security's passkey, password, or
OTT endpoints. Spring stores the authenticated context in Redis and sends the
browser an opaque HttpOnly `SESSION` cookie. `GET /api/auth/me` returns the
current enabled operator profile; `POST /api/auth/logout` invalidates the
session and requires the CSRF header. External/service clients continue using
validated bearer JWTs. Never put a signing key or server credential in browser
code.

## Passkey and email recovery endpoints

Spring Security WebAuthn exposes registration and authentication at
`/webauthn/register/options`, `/webauthn/register`,
`/webauthn/authenticate/options`, and `/login/webauthn`. Passkey challenges
require browser session state. Registration is for authenticated operators;
public self-registration is disabled.

Browser state-changing requests first call `GET /api/auth/csrf`, then send the
CSRF cookie and its token in `X-XSRF-TOKEN`. Email fallback uses
`POST /api/auth/one-time-token` with an `email` form field. Spring processes
the single-use email link at `/login/ott`. Unknown, disabled, and throttled
accounts receive the same accepted response. Tokens expire after five minutes
and requests are rate-limited per normalized email. Configure
`FORD_RESEND_API_KEY` and `RESEND_FROM` to deliver email. Never log or return
the magic link or token in the API response.

Operator identities are allowlisted in PostgreSQL. Provision operators through
the controlled onboarding process before they can use email fallback or
register a passkey; public self-registration is disabled.

## Token claims

| Claim | Requirement |
|---|---|
| `iss` | `https://ford.emme.business` |
| `aud` | Includes `ford-ai-service` |
| `sub` | Stable operator UUID |
| `exp`, `nbf` | Valid token lifetime |
| `roles` | Granted operator roles |
| `tenant_ids` | UUID tenant memberships |
| `scope` | Only scopes granted to the operator identity |

Spring maps scopes to `SCOPE_<scope>` and roles to `ROLE_<role>`. Tenant IDs
are checked at the existing tenant authorization boundary; clients must not
fabricate them in headers or query parameters. The identity record limits the
scopes placed in tokens.

## API security responses

- `401`: missing or invalid browser session, or missing, expired, malformed, or invalid bearer token.
- `403`: valid token without the required role, scope, or tenant access.
- `409`: authorized request conflicts with current resource state.

API errors use `application/problem+json` and follow the `ProblemDetail`
schema in `frontend-api.yaml`.
