# Post-login passkey enrollment implementation

| Field | Detail |
|---|---|
| Design | [Post-login passkey enrollment](../specs/2026-10-16-post-login-passkey-enrollment-design.md) |
| Status | Locally implemented; release pending |

## Tasks

- [x] Add Service unit coverage for registered, missing, and empty WebAuthn credentials.
- [x] Return `hasPasskey` only from authenticated `/api/auth/me`; document it in the API contract.
- [x] Add Web contract validation and prompt behavior tests.
- [x] Show an optional post-login prompt only when server status is false; keep Later and profile-menu registration.
- [x] Run Service unit tests; all tests passed.
- [ ] Run Service DB integration verification; local Testcontainers could not connect to Ryuk on the stopped Colima `emme` profile.
- [x] Run Web tests (138 passed), type check, API contract check (47 paths), and production build.
- [ ] Commit, push, and release Service first, then Ford Web through the approved workflows.
- [ ] Verify production enrollment and subsequent passkey login without contacting a customer.

## Release blocker

The current GitHub CLI credential is invalid (`gh auth status`); there is no
GitHub token in the active environment or `~/.zshrc`. Push/PR creation and the
Service-then-Web production release have not run.
