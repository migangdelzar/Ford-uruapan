# Post-login passkey enrollment

| Field | Detail |
|---|---|
| Date | 2026-10-16 |
| Status | Approved |
| Repositories | Ford-uruapan (Spring service), Ford-web (Pages UI) |

## Summary

Offer an optional passkey setup prompt after an operator signs in when that
operator has no registered passkey. Keep the operator signed in if they choose
“Later”, their browser does not support WebAuthn, or registration fails. Retain
the existing profile-menu action for adding another passkey later.

## Current behavior

- Spring Security owns passkey registration and authentication and persists
  WebAuthn credentials in its JDBC repositories.
- `GET /api/auth/me` returns the authenticated operator profile, but does not
  report whether a passkey is registered.
- Ford Web supports passkey sign-in and has a `registerPasskey` helper. The
  signed-in user menu invokes it, but there is no first-login enrollment prompt.
- Registration already uses the Spring registration options and attestation
  endpoints with same-origin credentials and the CSRF token.

## Design

### Session status

Extend the authenticated `/api/auth/me` response with one boolean field,
`hasPasskey`. Spring resolves the current operator's WebAuthn user entity from
the existing `PublicKeyCredentialUserEntityRepository`, then checks whether
`UserCredentialRepository.findByUserId` returns at least one credential. If no
user entity or credential exists, return `false`. Do not return credential IDs,
labels, public keys, or other authenticator data.

This is a read-only status check. It introduces no schema migration and does
not issue or store a registration challenge.

### Enrollment experience

After the authenticated session check, Ford Web shows a dismissible enrollment
dialog only when `hasPasskey` is `false`. The dialog explains that a passkey
allows faster sign-in and offers:

- **Set up passkey:** call the existing registration helper, which requests a
  fresh challenge, invokes the browser WebAuthn API, and submits the attestation
  with CSRF protection.
- **Later:** dismiss the prompt and continue into the app without changing the
  authenticated session.

On successful registration, close the prompt and continue normally. On browser
unsupported, user cancellation, network error, or server rejection, show a
concise error in the dialog and keep both the session and “Later” action usable.
The existing user-menu action remains available for adding another passkey.

The server remains authoritative for `hasPasskey`; the browser does not infer
credential state from local storage or registration-option side effects.

### Release sequence

1. Release the Ford Service response change first; old clients ignore the added
   JSON field.
2. Release the Ford Web prompt after the service reports `hasPasskey`.
3. Roll back the UI independently if needed. Rolling back the service field is
   also backward compatible with the previous UI.

## Alternatives considered

1. **Server-reported status with an optional prompt (selected).** Avoids
   prompting operators who already have a passkey. Requires a small service
   response change and a UI onboarding component.
2. **Prompt after every password or email login.** Smaller service change, but
   can ask passkey users to enroll again and cannot accurately cover all login
   methods.
3. **Keep only the user-menu action.** No implementation or deployment risk,
   but setup remains undiscoverable to operators who need it.

## Security and privacy

- Only an authenticated operator can read their own passkey status or register
  a credential.
- Preserve Spring Security's WebAuthn challenge validation, origin/RP checks,
  same-origin cookies, and CSRF enforcement.
- Do not log or expose passkey credential material.
- Enrollment is optional and never blocks CRM access.

## Verification plan

### Ford Service

- Authenticated profile response reports `hasPasskey=false` when no credential
  is stored and `true` when one or more credentials are stored.
- Missing WebAuthn user entity is treated as no registered passkey.
- Anonymous `/api/auth/me` remains unauthorized.
- Existing passkey registration and authentication tests remain green.

### Ford Web

- Prompt appears only for an authenticated profile with `hasPasskey=false`.
- Prompt is absent for `hasPasskey=true` and while auth state is unresolved.
- “Later” dismisses the prompt without logging out or blocking the CRM.
- Successful registration closes the prompt; cancellation/failure keeps the
  error and dismissal path usable.
- Existing profile-menu registration and sign-in flows remain functional.

### Production rollout

- Run unit, component, service integration, and production-build checks before
  release.
- Release service first, verify the expanded `/api/auth/me` response without
  exposing credential details, then publish Ford Web.
- Verify a password/email login with no credential shows the prompt, passkey
  enrollment succeeds, subsequent session status is true, and a passkey login
  reaches the CRM.

## Scope boundaries

- No forced enrollment or loss of non-passkey recovery methods.
- No passkey-management API for deleting or renaming credentials.
- No redesign of OAuth or one-time-token login in this feature.
- No change to public hostnames, Spring UI proxying, or Pages routing.
