# Cloudflare Pages origin cutover and rollback

## Current state

Cloudflare Pages cannot rename a `pages.dev` hostname in place. The previous Pages project is `ford-web-ui`, with origin `https://ford-crm-ui.pages.dev`. The replacement is a separate project, `emme-crm-ui`, with origin `https://emme-crm-ui.pages.dev` and an independent deployment history. Both projects must remain available during the cutover.

The browser origin remains `https://ford.emme.business`. Spring serves UI routes through its server-side Pages proxy; API, login, OAuth, WebAuthn, and webhook routes remain Spring-owned. This migration changes neither DNS nor the public hostname.

The new Pages origin was deployed from verified Ford Web `main` commit `c8ee6d38ced0b5a4ec87471c0564ffb1c673bd2e` by [Actions run 38588412862](https://github.com/migangdelzar/Ford-web/actions/runs/38588412862). Deployment `0056b296-f347-42ad-b73e-31fc735925d1` passed checks for `/`, `/login`, `/dashboard`, and a fingerprinted asset. This verifies the Pages origin only; the Spring upstream cutover and public-path verification remain pending.

Ford Web's production `FORD_WEB_SMOKE_URL` now points to `https://ford.emme.business`, so a future gated Pages release checks the public browser origin and Spring API path instead of testing a `pages.dev` URL directly.

## Cutover sequence

1. In Ford-web, merge the project-name and workflow changes to `main`. Run **Stage and verify emme Pages origin** from `main`. It checks successful CI for the selected commit, builds and tests, deploys the `main` production branch to `emme-crm-ui`, then checks `/`, `/login`, `/dashboard`, and a fingerprinted asset at the new Pages origin.
2. Confirm the new deployment is successful in Cloudflare Pages. The new project's history starts with its first deployment; previous deployment records are not transferred from `ford-web-ui`.
3. In Ford Service, merge the Spring default-upstream change to `main`. The default becomes `https://emme-crm-ui.pages.dev`. To control the origin explicitly, set the GitHub `production` environment variable `FORD_WEB_UI_UPSTREAM_URL`; the release workflow forwards that variable to Ansible. Leave it unset to use the new default.
4. Deploy the selected, CI-verified Ford Service image through the existing release workflow only after its production readiness gates are satisfied. Verify through `https://ford.emme.business`:
   - `/`, `/login`, an authenticated application route, and fingerprinted JS/CSS assets render.
   - API health and representative API requests are handled by Spring.
   - Login, logout, OAuth authorization/callback, WebAuthn, and webhook routes remain Spring-owned and behave as before.
   - Redirect `Location` headers and browser navigation remain on `ford.emme.business`; no redirect reveals either Pages hostname.
   - Cookies, security/cache headers, and static asset caching retain existing proxy behavior.
5. Keep `ford-web-ui` and `ford-crm-ui.pages.dev` deployed throughout verification and the agreed rollback window. Do not remove or detach the previous project as part of this procedure.

The Pages staging workflow verifies only the new Pages origin directly. Public Spring proxy and authentication checks require a Ford Service deployment and an available route to `ford.emme.business`; record their results before calling the cutover verified.

## Rollback

1. In the GitHub `production` environment for Ford Service, set `FORD_WEB_UI_UPSTREAM_URL` to `https://ford-crm-ui.pages.dev`.
2. Dispatch the Ford Service release workflow with `operation=deploy` and the full SHA of the last known-good Ford Service commit. The workflow requires successful CI for that SHA and deploys its immutable GHCR digest. The upstream override is read by the Ansible app environment template.
3. Verify `/`, `/login`, an application route, assets, API/auth routes, and redirect host through `https://ford.emme.business`. Confirm that browser redirects do not expose `pages.dev`.
4. Keep the `emme-crm-ui` project intact for diagnosis. To retry the cutover, set the override to `https://emme-crm-ui.pages.dev` or remove it to use the new default, then deploy a verified Ford Service image.

This rolls back only the Spring upstream choice. It does not roll back or delete either Pages deployment and does not change DNS.

## Cloudflare account actions and limitations

- The replacement Pages project `emme-crm-ui` has been created separately. Cloudflare does not transfer the old project's deployment history when changing to a new `pages.dev` subdomain.
- The Pages staging workflow uses the existing GitHub `pages-preview` environment token. That token must have permission to deploy to the new Pages project; a failed authorization requires an account owner to grant the needed Pages project access or update the environment token.
- No DNS record, custom-domain association, Tunnel route, or public domain setting is changed by this project migration.
- Keep the old project until the new Pages origin and Spring-proxied production path have both been verified. Project cleanup, if later desired, is a separate action after the rollback window.
