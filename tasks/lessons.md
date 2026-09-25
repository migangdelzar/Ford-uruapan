# Implementation lessons

- Spring proxies require transactional adapter classes to remain subclassable; do not mark a proxied repository `final`.
- Keep tests aligned with record accessor names when extending immutable decision objects (`handoffRecommendation()` rather than a shortened alias).
- Spring Modulith module boundaries include configuration method signatures; cross-module diagnostics should consume primitive properties rather than non-exposed configuration types.
