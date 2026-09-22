# Implementation lessons

- Spring proxies require transactional adapter classes to remain subclassable; do not mark a proxied repository `final`.
- Keep tests aligned with record accessor names when extending immutable decision objects (`handoffRecommendation()` rather than a shortened alias).
