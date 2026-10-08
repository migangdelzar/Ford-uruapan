# Implementation lessons

- Spring proxies require transactional adapter classes to remain subclassable; do not mark a proxied repository `final`.
- Keep tests aligned with record accessor names when extending immutable decision objects (`handoffRecommendation()` rather than a shortened alias).
- Spring Modulith module boundaries include configuration method signatures; cross-module diagnostics should consume primitive properties rather than non-exposed configuration types.
- Separate-service placement must follow the user's explicit topology: local MCP + its own Cloudflare ingress, with only the client URL configured remotely; do not move a workload to an Emme VM because adjacent services run there.
- The repository ignores directories named `out/` globally, which hides new Java adapter sources and tests under `adapter/out/`; check `git check-ignore` for new paths and place new files where they remain visible.
- Live provider tests depend on external model/config state; disable Gradle up-to-date reuse or a “passing” preflight may be a stale result.
