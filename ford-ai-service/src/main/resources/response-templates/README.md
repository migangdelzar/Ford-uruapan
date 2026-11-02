# Catalog fact response templates

Spring renders these templates after the model/tool turn. `{{...}}` values are
substituted by `CatalogFactResponseRenderer`; the LLM does not author or revise the
deterministic price/promotion response. Templates may show only values returned
by the catalog tools: published price, promotion benefit, dates, conditions, and Ford source
links. Do not calculate a net price or claim a promotion is active when the tools do
not confirm it. General model/comparison answers may append a short source-link
block from the exact URLs returned by the tools used in that turn.

- `vehicle-price.md`: price/cotization request, with the model page link.
- `promotion.md`: promotion request, with verified promotion/model links.
- `price-and-promotion.md`: combined request; sections rendered independently.
- `price-unconfirmed.md` and `promotion-unconfirmed.md`: safe wording if catalog
  evidence is missing or unusable.
- `source-links.md`: clickable raw URLs, formatted for WhatsApp, from vehicle,
  trim, model-year, competitor-comparison, promotion, and page-link results.

Links are rendered only when their HTTPS host is `forduruapan.mx` or a subdomain.
URL query strings and fragments are removed before rendering.
Only HTTPS URLs on `forduruapan.mx` or its subdomains are eligible. Never infer a
Mexico-market fact or construct a route from a model name. When the catalog lacks an
exact year/trim or paired comparison fact, state that it could not be confirmed.
