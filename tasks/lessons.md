# Implementation lessons

- Spring proxies require transactional adapter classes to remain subclassable; do not mark a proxied repository `final`.
- Keep tests aligned with record accessor names when extending immutable decision objects (`handoffRecommendation()` rather than a shortened alias).
- Spring Modulith module boundaries include configuration method signatures; cross-module diagnostics should consume primitive properties rather than non-exposed configuration types.
- Separate-service placement must follow the user's explicit topology: local MCP + its own Cloudflare ingress, with only the client URL configured remotely; do not move a workload to an Emme VM because adjacent services run there.
- The repository ignores directories named `out/` globally, which hides new Java adapter sources and tests under `adapter/out/`; check `git check-ignore` for new paths and place new files where they remain visible.
- Live provider tests depend on external model/config state; disable Gradle up-to-date reuse or a “passing” preflight may be a stale result.
- Short affirmations must be interpreted against the immediately pending offer/question. Classifying only the literal reply (for example, “sí me gustaría”) can fall back to GENERAL and restart discovery; persist the next workflow slot deterministically and do not depend on generated prose to advance state.
- Spring Security OAuth2 Resource Server validates access tokens; it does not issue them. Before designing login, inspect `SecurityConfiguration` and use the same external OIDC issuer, rather than describing Spring as the issuer.
- Spring Modulith observability creates CGLIB proxies for observed beans; keep observed Spring components subclassable. `final` classes can prevent the app context from starting.
- A shared deployment action may execute Ansible roles from the caller repository; when duplicated templates exist, verify the effective runtime config and edit its source, then confirm behavior through a production request.
