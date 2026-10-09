# Frontend authentication contract

Ford-service owns operator authentication. Spring Authorization Server issues
tokens; Spring Security WebAuthn handles passkeys, and Spring one-time-token
(OTT) login sends a single-use email link as the recovery path. The Ford-web
BFF uses authorization code with PKCE and keeps OAuth tokens server-side.

## Configuration

When first-party auth is enabled, configure these server-side values:

```text
FORD_SECURITY_AUTH_ENABLED=true
FORD_SECURITY_ISSUER_URI=https://ford.emme.business
FORD_SECURITY_AUDIENCE=ford-ai-service
FORD_SECURITY_JWK_PRIVATE_KEY=<base64-encoded PKCS#8 RSA private key>
FORD_BFF_OIDC_CLIENT_ID=ford-web-bff
FORD_BFF_OIDC_CLIENT_SECRET=<server-side secret>
FORD_BFF_OIDC_REDIRECT_URI=https://ford.emme.business/api/auth/callback
FORD_RESEND_API_KEY=<Resend API key>
RESEND_FROM=<verified sender>
```

The provider metadata is at
`https://ford.emme.business/.well-known/openid-configuration`. Production
serves the UI and API under `ford.emme.business`; the reverse proxy sends
`/api/**` to ford-service and the remaining UI paths to Ford-web.

## Browser login

Use the registered Ford-web BFF confidential client with Authorization Code
and PKCE:

1. Generate a `code_verifier` and `code_challenge` in the BFF.
2. Redirect the operator to `/oauth2/authorize` with `response_type=code`,
   `client_id`, `redirect_uri`, `scope`, `state`, and the PKCE challenge.
3. Exchange the authorization code at `/oauth2/token` from the BFF.
4. The BFF stores access and refresh tokens server-side and gives the browser
   only an opaque Secure, HttpOnly, SameSite session cookie.

Never put a client secret or signing key in browser code. The API accepts
access tokens as bearer tokens from the BFF.

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

- `401`: missing, expired, malformed, or invalid access token.
- `403`: valid token without the required role, scope, or tenant access.
- `409`: authorized request conflicts with current resource state.

API errors use `application/problem+json` and follow the `ProblemDetail`
schema in `frontend-api.yaml`.
