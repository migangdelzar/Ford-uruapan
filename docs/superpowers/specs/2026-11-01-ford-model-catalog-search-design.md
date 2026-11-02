# Ford Model Catalog and Hybrid Search Design

## Goal

Represent one logical catalog per Ford model, keep trim/year facts structured,
and retrieve qualitative catalog information with one PostgreSQL statement that
combines semantic and keyword ranking. Preserve source citations, exact filters,
the approved scraper snapshot, and existing cutover gates.

## Confirmed decisions

- Catalog is logically organized by model, not by trim.
- Qualitative search finds a model first; exact SQL filters determine which
  model-year and trim records are returned.
- Trim-level searchable documents are added only when a source contains
  materially distinct information for that trim.
- Embeddings stay on searchable catalog documents, not on model or trim rows.
- One document represents one coherent source text/chunk, with its own citation
  and vector. A logical model catalog can have multiple such documents.
- Use the currently approved embedding contract: `bge-m3`, 1,024 dimensions.
  Do not combine catalog vectors with conversation-memory vectors.
- Keep all application-owned objects out of PostgreSQL's `public` schema,
  including Liquibase history, catalog and metadata tables, indexes, and the
  pgvector extension. Keep the built-in `public` schema present but empty; do
  not drop it.
- Install or relocate the relocatable pgvector extension into `ford_catalog`.
  Qualify its vector types and operator classes with `ford_catalog`; grant the
  catalog reader only the catalog schema usage and approved catalog table reads.
  Keep metadata table access denied.
- Combine vector and Spanish keyword ranks with reciprocal-rank fusion (RRF)
  inside one parameterized PostgreSQL query. Query embedding generation is a
  separate model call, not a second database query.
- Structured facts (including price, color, year, and trim) remain authoritative
  SQL data. Semantic similarity must not invent or override exact values.
- Preserve source URLs on every returned document/fact used for an answer.
- Do not alter approved source archive bytes, approved manifest hashes, or an
  applied Liquibase changeset to make the design fit current exporter output.
- Keep catalog tools disabled until existing snapshot-integrity and release
  gates pass. No deployment, scraper behavior, routing, DNS, or Cloudflare
  changes are part of this work.

## Logical data model

```text
Model catalog identity
  ├── Model-year records
  │     └── Trim/variant records
  │           └── Exact facts, prices, and colors
  └── Search documents/chunks
        ├── model identity (required)
        ├── model-year identity (optional)
        ├── trim/variant identity (optional)
        ├── source URL and observation metadata
        ├── searchable text and generated keyword vector
        └── bge-m3 vector(1024), status, and model identifier
```

The model identity is the semantic grouping boundary. A model-level document
must remain searchable when `variant_id` is null. Model-year and variant links
provide narrower scope when the evidence is year- or trim-specific. Exact
variant facts stay in the structured relations and are not copied into a
semantic vector as a substitute for SQL predicates.

Before implementing any schema or import changes, inspect the approved snapshot
to prove that its model/document/year/variant identifiers provide stable,
unambiguous relationships. The disposable restore audit found 30 stable model
IDs, with exact model-year joins for model documents and exact offer/model/year
joins for offer-only documents. Recheck identities against the approved archive
before loading it. If any mapping is ambiguous, stop and resolve it with the
source owner. Do not infer model identity from fuzzy text matching.

The catalog document contract permits nullable `model_id`, `model_year_id`, and
`variant_id` fields. A read-only audit on the independently restored candidate
found stable joins for its 1,882 documents, including the 2 offer-only documents
resolved through exact offer IDs and exact model/year equality. The approved
archive hash mismatch still blocks treating the candidate as approved or loading
its rows. Keep exact identity joins; fail closed on any missing or ambiguous
mapping. Do not create competing canonical model data or rewrite the approved
source snapshot as part of this design.

## Current blockers

- The fail-closed Liquibase exporter still reports hash mismatches for
  `ford_capture.retrieval_document_manifest` and
  `ford_read.catalog_documents_v1` against the approved manifest. The archive
  has been restored independently, but canonical hashing for those relations
  remains unresolved. Do not generate or load a snapshot changeset until this
  discrepancy is explained and every approved hash matches.
- The candidate is not production-validated and has known coverage and
  historical-price gaps. Do not infer missing color or availability data.

## Search flow

1. Validate query text, filters, result limit, and the query embedding's exact
   dimension and finite values.
2. Generate one `bge-m3` query vector outside PostgreSQL.
3. Run one parameterized SQL statement. Its CTEs:
    - select each model's best ready, compatible semantic document, then bound
      the semantic candidate set by distinct models;
    - select each model's best Spanish full-text document, then bound that
      candidate set by distinct models;
   - apply model and model-year scope without requiring a trim link;
   - apply exact price/color/trim constraints against structured relations;
   - assign deterministic ranks and compute RRF scores;
    - apply RRF to model ranks, not raw scores or chunk counts, so models with
      more chunks do not crowd out other models; retain the best semantic and
      keyword evidence documents and citations, deduplicating when they are the
      same document; and
   - return only matching model-year/trim facts and source references.
4. Return model-level results with the best semantic and keyword evidence
   documents/citations (deduplicated when both channels select the same
   document) and exact matching trim facts. The SQL statement may emit one row
   per evidence-document/trim pair; the adapter groups rows into the model-level
   result in memory without another database roundtrip. A model with no matching
   structured trim remains a model result only when the user's filters do not
   require such a trim.
5. Return no unsupported exact fact. Missing price/color/spec data remains
   unknown, not an inferred value. No availability claim is allowed without a
   trusted stock feed.

The single statement is the default hybrid path. If exact-only questions can be
answered by a cheaper dedicated SQL method, they may continue to use that
method; “one query” means avoid separate vector and keyword database roundtrips
for a single hybrid request, not force every tool operation into one query.

## Ranking, bounds, and indexes

- Use deterministic document-ID tie-breaking for both semantic and keyword
  rankings, and deterministic model/result tie-breaking after RRF.
- Keep bounded candidate and result limits to prevent broad catalog scans from
  returning unbounded output.
- Retain separate vector and keyword ranking signals; do not compare raw cosine
  distance directly with `ts_rank` values.
- Verify or add indexes only after reviewing the actual query plan and snapshot
  cardinalities. Candidate indexes include a pgvector HNSW cosine index, a GIN
  index for the generated `tsvector`, and B-tree indexes for model/year/variant
  joins and frequently used exact predicates.
- Keep operator resolution and extension ownership explicit. Do not drop
  `public` or use `CASCADE`. Moving pgvector into `ford_catalog` is approved only
  after auditing extension relocatability, ownership, dependencies, and any
  existing public-schema objects; fail closed if the review finds anything not
  explicitly classified for relocation.

## Compatibility and migration safety

- Preserve the scraper's source relations and monthly sync behavior.
- Do not migrate or transform source documents until the approved archive hash
  procedure and exporter verify every manifest relation.
- Any future schema/index change is a new Liquibase changeset; never edit an
  already-applied changeset.
- The reader continues using `ford_catalog_reader`, with read-only access to
  catalog read relations only. It must not access metadata, capture, or
  provenance relations.
- Keep the catalog feature flag disabled until data, grants, health, and the
  pre-existing release gates pass.

## Schema placement and public-schema invariant

- Use `ford_metadata` for app-owned data and Liquibase history; use
  `ford_catalog` for catalog relations and the pgvector extension objects.
- Add extension-schema setup before the first metadata vector changeset so
  clean installs create `ford_catalog.vector` and its operator classes before
  use. For an existing install, verify pgvector is relocatable and owned by the
  migration principal before moving the extension with
  `ALTER EXTENSION ... SET SCHEMA`; do not uninstall/reinstall or cascade.
- Qualify vector columns, casts, operators, and operator classes. Configure JDBC
  search paths with only the required `ford_metadata` and/or `ford_catalog`
  schemas; do not append `public` as a fallback.
- Preserve the `ford_catalog_reader` boundary: it receives `USAGE` on
  `ford_catalog` and `SELECT` only on approved catalog read relations. It gets no
  `USAGE` on `ford_metadata`, and no capture, provenance, manifest, or write
  privileges.
- Audit all object classes in `public` before migration. Move only explicitly
  reviewed app objects, relocate pgvector as above, and fail the migration if
  any unclassified user object remains. Do not drop `public`, use `CASCADE`, or
  change approved archive contents.
- Verify after migration that `public` contains no application-owned tables,
  views, sequences, types, functions, operators, indexes, or extensions, and
  that all Liquibase history is in `ford_metadata`.

## Verification requirements

### Unit tests

- Invalid dimensions, null vectors, and non-finite vector values fail before
  SQL execution.
- Blank query and non-positive result limits return no results.
- Result limits remain bounded; SQL values remain parameterized.

### PostgreSQL integration tests

- A model-level document with no `variant_id` is found by model search.
- Model-year and trim filters return only exact structured matches.
- Price/color filters do not treat semantic similarity as an exact match.
- Keyword-only, semantic-only, and overlapping matches fuse deterministically
  with no duplicate model result and citations retained.
- The hybrid path executes exactly one catalog SQL statement.
- The pgvector extension, types, operators, and operator classes resolve from
  `ford_catalog`; app data and Liquibase history resolve from `ford_metadata`;
  `public` has no application-owned objects.
- The read-only role can retrieve approved catalog results and remains denied
  metadata, capture/provenance access, and writes.
- Query plans use intended indexes at representative candidate scale, or an
  explicit reason is recorded if PostgreSQL selects a sequential scan.

### Release gates

- Snapshot hashes and row counts match the approved immutable manifest before
  any snapshot changeset is generated.
- Existing app health, role, tool-off, consent, and protected release gates
  remain unchanged.
- No deployment or customer-facing scraper change is included.

## Non-goals

- Redesigning conversation-memory embeddings or tenant knowledge.
- Replacing scraper-owned canonical facts or its monthly crawler.
- Adding per-trim embeddings where no trim-specific source text exists.
- Claiming stock availability or making candidate data production-validated.
- Resolving the existing source-hash mismatch by changing approved hashes.
