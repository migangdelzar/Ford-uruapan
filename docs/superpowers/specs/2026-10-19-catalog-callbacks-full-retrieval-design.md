# Ford Catalog Callbacks and Full Retrieval — Design

## Goal

Enable Ford Service's 12 in-process, read-only catalog callbacks in production and complete the Service-owned semantic retrieval path, with verified least-privilege PostgreSQL access, source-grounded answers, and protected rollback.

## Current state

- `ford.catalog.tools.enabled` is bound from `FORD_CATALOG_TOOLS_ENABLED` and defaults to `false` in `application.yml` and `application-prod.yml`.
- When disabled, `CatalogDataSourceConfiguration` creates no catalog datasource/port and Spring AI registers no local catalog callbacks. Ford Service `v0.1.26` deployed with this default; its catalog-reader permission check was skipped.
- The callbacks exist in `CatalogTools`, but `DecisionPolicy` does not currently allow `compare_trims`, `compare_model_years`, `get_variant_comparisons`, or `get_page_links` for any tool family.
- Catalog reads are lexical SQL today. `search_offers` truthfully reports semantic search as unavailable. Ford-scraping stores BGE-M3 vectors with one thousand twenty-four dimensions, but `ford_read` currently exposes no document-embedding view; Service's default embedding model is `nomic-embed-text` and production Ollama endpoint/Access settings are not configured. Current production settings expose no Service Ollama Access secret mapping, and the deployment template defaults to `https://ollama.example.invalid`.
- Existing database reader design is approved: use `ford_catalog_reader` with the existing database-password value, not a new Vault password. Node/MCP byte-for-byte parity remains retired by user decision; Service-owned behavior and retrieval quality remain in scope.
- Release-source correction: use Ford-scraping `origin/main` (`5227f5e`) for migrations 022–025 and the protected release workflow; the local `origin/HEAD` pointer targets a separate branch missing these views. Ford-scraping `main` pins action SHA `a878ad1ba1dd4a25ef814ea4b69ac817ae32165a`. The local action clone cannot fetch that SHA by ID; verify its exact upstream contents before relying on it. The local action `origin/main` has reader provisioning, and the X509 POST-probe fix is available on `origin/fix/scheduler-x509-post-probe`.
- Screenshot root cause: `ToolAuthorizationPolicy` turns callback exceptions into a model-visible unavailable string without retry; `SpringAiAssistantService` turns uncaught failures into a generic apology; `DealershipResponsePlanner` appends after-hours and holiday details to unrelated replies.

## Architecture

1. Ford-scraping remains the only schema/migration/catalog writer owner. Add an additive security-barrier `ford_read` view for source-backed catalog documents and embeddings. Expose only stable document ID/key/type, model/year/trim/offer context, semantic content, full-text vector, embedding, embedding model/status, exact source URL, and observation time.
2. Provision or repair `ford_catalog_reader` through the protected scheduler release after its verified backup and migrations. The current Ford-scraping release passes the existing PostgreSQL Vault OCID to the shared action; current shared-action main includes the idempotent reader grant, while the X509 POST probe must first be ported from its review branch. Grant only `CONNECT` on `ford_service`, `USAGE` on `ford_read`, and `SELECT` on current/future read views. Remove role memberships, base-schema and write grants; set `default_transaction_read_only=on`. Prove allowed reads and denied writes before replacing the active scheduler.
3. Ford Service keeps its separate `catalogDataSource` and reuses `FORD_DATABASE_PASSWORD`; configure its private JDBC URL and `ford_catalog_reader` username. Preserve the fail-closed default flag.
4. Fix `DecisionPolicy` routing so all 12 callback names are reachable only from appropriate read-only intent families. Include comparison, official competitor comparison, page-link, and catalog-status use cases. Expand internal-response markers and diagnostics for every callback name; preserve tenant auth and evidence grounding. The model may initiate each tool once per turn; the adapter may make one additional silent attempt only for classified transient failures.
5. For free-text discovery, query using the exact stored BGE-M3 model and 1,024 dimensions through a catalog-specific embedding adapter; do not change the global embedding model used by knowledge or conversation memory. Build candidate lists from vector, full-text, and trigram search; apply model/year/trim/effective-date constraints before ranking; fuse with the existing `ReciprocalRankFusion` utility. Keep exact lookups, prices, promotions, and comparisons on structured views.
6. Keep lexical fallback when vector service is missing or fails, and report semantic state as `unavailable`; never call lexical results semantic. Never expose the Ollama endpoint publicly. Resolve the existing Cloudflare Access service-token path for the BGE-M3 endpoint; reuse an already-provisioned Vault secret only if its identity and read policy are verified. If no usable protected credential exists, stop before enabling semantic retrieval and request approved credential provisioning.
7. Evaluate the existing Jev reranker only after recall is established. Enable it only if the reviewed retrieval set shows a quality gain within measured latency/resource constraints. Do not add a second RAG, reranker, or orchestration stack.
8. Add a conditional, content-free catalog readiness check that performs a bounded read against a required `ford_read` view when callbacks are enabled. Include it in release readiness so an unreachable or incomplete catalog source cannot pass service health; never return secrets or raw document content in health details.
9. Keep customer replies short, natural Mexican Spanish, and limited to the facts requested. For price/promotion answers, include only source-verified model/year, price and currency, promotion benefit, validity, and material conditions; include no more than one source link for each fact. Do not add after-hours or unknown-holiday text unless the customer asks about hours/special hours or explicitly requests follow-up.
10. Distinguish failed catalog calls from successful empty results. Retry transient catalog-tool failures once silently; keep the existing bounded provider failover. If verified current-turn catalog facts exist, render only those facts; do not use stale memory for prices/promotions. If no verified answer remains after the tool retry or provider failover, do not create or send a customer reply; propagate a typed retry signal so the existing durable inbound publication recovery can retry. Keep its current five-attempt bound; leave unresolved turns pending for review after the limit. Do not retry permanent authentication, authorization, or invalid-request failures automatically; route them to unresolved review state without committing an outbound turn. A successful catalog call with zero matches may return a brief, source-grounded no-result answer.

## Evaluation and grounding

- Build exactly 100 reviewed Spanish questions from the existing 198-row `evals/catalog-qa/questions.jsonl` seed plus approved knowledge fixtures. The seed spans multiple callback categories but is mostly vehicle detail. Revalidate every expected source/fact against current `ford_read` views; do not treat its generated reference answers as ground truth. Label expected route/tool family, model/year/trim selectors, relevant stable document keys, and volatile-catalog versus approved-narrative intent.
- Compare lexical baseline, current fusion, and RRF using hit@5, recall@5, MRR, nDCG@5, answer fact coverage, abstention correctness, and p50/p95 retrieval latency. For positive cases, require recall@5 >= 0.90 and nDCG@5 no worse than the lexical baseline; require answer fact coverage >= 0.90 and p95 retrieval latency <= 2 seconds. Preserve the baseline unless a candidate improves the reviewed cases without degrading grounding or latency.
- Require exact selectors to remain exact, all prices/promotions/vehicle claims to be supported by current source evidence, zero unsupported factual claims/never-promise violations, and successful no-result cases to state only the grounded absence. Keep the existing evidence-ledger and `EvidenceGroundedResponsePolicy`; no customer-facing factual claim may rely on semantic similarity alone.
- Add failure-flow tests proving transient catalog/provider errors are retried without customer-facing error text, same-turn verified facts survive partial failures, and an empty successful result remains distinct from a failed call. Prove no outbound turn is committed when no verified answer exists, permanent failures are not automatically resubmitted, and hours/holiday notices do not appear on greetings or catalog answers unless requested.

## Protected release sequence

1. Run Service unit tests and synthetic PostgreSQL integration tests for callback registration, policy routing, datasource connection, document-view projection, ranking/fallback, tenant/scope boundaries, evidence grounding, and the reviewed retrieval set.
2. Publish the current Ford-scraping scheduler candidate through its protected workflow. It must verify backup-before-migration, provision/repair the SELECT-only reader, prove writes/base-schema access are denied, and retain exactly one healthy scheduler.
3. Configure production `FORD_CATALOG_JDBC_URL`, `FORD_CATALOG_USERNAME=ford_catalog_reader`, and the verified BGE-M3 endpoint/Access reference. Keep `FORD_CATALOG_TOOLS_ENABLED=false` until the reader and semantic model gates pass.
4. Release Ford Service through the protected coordinator with `FORD_CATALOG_TOOLS_ENABLED=true`. Its pre-deploy reader check must pass; deployment health, callback registration, database health, and a source-backed catalog smoke must pass before completion.
5. Run the read-only post-deployment preflight; verify Service/Collector health, PostgreSQL readiness, one scheduler, and that callback names are registered. Roll back the Service image or turn the flag off if any check fails. Keep the scheduler, Cloudflared, Ollama, and their shared volume intact.

## Non-goals

- Do not restore the retired Node MCP server or require its byte-for-byte outputs.
- Do not give Service a catalog writer login or create a new database password secret.
- Do not expose the Ollama service publicly or mark semantic search ready while using lexical fallback.
- Do not enable reranking or change retrieval algorithms without measured improvement.
- Do not use stale conversation memory as current price/promotion evidence or send generic tool/provider failure text.

## Acceptance

- All 12 callbacks are registered when enabled, routed by `DecisionPolicy`, and absent while the flag is false.
- `ford_catalog_reader` can read `ford_read` views, cannot write, cannot access base `ford`, and has no inherited role membership.
- With callbacks enabled, Service readiness performs a read-only query through the dedicated datasource and fails closed when credentials or required views are unavailable.
- BGE-M3 query and stored vector model/dimension match; vector, lexical, and trigram results are measured on the reviewed 100-query set with semantic readiness accurately reported.
- Tool responses preserve exact source/observation evidence; unsupported price, promotion, model/year/trim, and competitor claims are rejected or abstained.
- Price/promotion replies are concise and source-grounded; transient tool failures retry silently, and unresolved turns send no customer-facing failure message.
- After-hours and holiday details appear only when relevant to the customer's request.
- Protected scheduler and Service releases pass their backup, permission, health, callback-registration, and source-backed smoke gates; post-release preflight passes and rollback remains available.
