# Ford Host-Stack Replacement Design

**Status:** Design approved in conversation; awaiting written-spec review. This is not production-change authorization.

## Goal

Replace the Ford-owned workloads on the existing `emme-1` and `emme-2` VMs in one approved maintenance window with a clean, empty stack. Preserve the VMs and shared host/network infrastructure. Do not carry forward old application, catalog, or Redis data into the active replacement.

## Scope

Replace or rebuild these Ford workloads:

- `emme-1`: Ford Service and its Collector.
- `emme-2`: PostgreSQL, Redis, Ford scraping/scheduler, Ollama, and the current MCP service/tunnel.
- Ford Service catalog callbacks use in-process Spring AI tools and the dedicated read-only PostgreSQL adapter. The remote MCP catalog path is not part of the target Service request path.

Retire the MCP service and its dedicated tunnel only after the replacement Service passes acceptance and its rollback period is complete. Keep external Cloudflare Pages, public DNS, and shared networking out of scope. Do not alter host OS, VM identity, or unrelated services.

## Data and retention policy

- The active replacement starts completely empty: no old conversations, leads, credential records, catalog rows, or Redis state. No snapshot or source data is loaded during this replacement. Provision new, rotated runtime/database credentials through the approved secret path; never copy old secret values.
- Deploy the scraper/scheduler in a paused state. It must not scrape, publish, or write until a separate ingestion authorization is approved.
- Catalog tools remain explicitly disabled (`FORD_CATALOG_TOOLS_ENABLED=false`) while the catalog is empty. Enablement requires a later approved source/data load, least-privilege verification, and protected release gate.
- Before the outage, create a full, encrypted, off-host backup of the current databases and required rollback artifacts; hash and manifest them, and prove restore in isolation. These artifacts exist only for rollback/recovery and are not copied into the new active database.
- Retain backups and old image/config digests through acceptance and the approved rollback window. Deletion is a separate irreversible operation requiring data-owner, security/retention, and release approval. The conversational preference to delete after cutover is not authorization to delete immediately or bypass retention policy.

## Replacement sequence

1. **Clear preconditions:** confirm exact host/service inventory; data-owner authorization for the empty reset; target-specific backup and restore proof; migration/bootstrap review; least-privilege roles; capacity; runtime secret access; protected release controls; health, rollback images, and operator access. Preserve the approved catalog archive and its hashes unchanged.
2. **Rehearse:** build and test the clean empty database and complete stack from reviewed immutable artifacts in disposable infrastructure. Verify startup and rollback without using production data or endpoints.
3. **Enter maintenance window:** announce the full outage, stop inbound application work and all writers, and take a final consistent backup. Confirm backup integrity and restore readiness before any destructive operation.
4. **Replace data services:** recreate PostgreSQL and Redis from reviewed configuration. Apply only the reviewed bootstrap/migrations. Create database `ford` with application schemas `ford_metadata` and `ford_catalog`, plus reviewed least-privilege roles. Keep PostgreSQL `public` present and empty of application/extension objects; install extensions only in reviewed approved schemas. Load no historical or catalog data.
5. **Replace application workloads:** start Ollama, Collector, Ford Service, and scraper/scheduler in the reviewed order. Keep scraper/scheduler paused and catalog tools false. Do not start the MCP service or its dedicated tunnel.
6. **Verify:** run health, authentication, schema/ledger, extension-placement, role grant/deny, secret-resolution, and empty-database checks. Confirm no old data exists in active PostgreSQL/Redis. Confirm Service uses Spring AI in-process tools and does not call remote MCP. Catalog tools must remain unavailable/disabled while no catalog exists.
7. **Accept and observe:** release owner records acceptance, outage end, and rollback-window start. Keep old images/config and encrypted backup available. Do not change public DNS, Cloudflare Pages, or shared routing.
8. **Retire old stack later:** after the rollback window and separate data-owner/security/retention/release approval, remove stopped old Ford containers, database/cache volumes, MCP/tunnel resources, obsolete config, and then retained backups using the approved cleanup process. Do not remove host OS or shared infrastructure. Record sanitized evidence; never log secret values.

## Rollback and stop rules

- Any missing precondition, backup/restore failure, unexpected data, migration/checksum mismatch, unsafe grant, secret failure, capacity shortfall, failed health/acceptance check, or unavailable rollback artifact stops the operation.
- Before acceptance, restore the prior approved images/config and the verified full backup. This may restore the old data temporarily; it is rollback, not the target end state.
- Do not automatically reverse Liquibase. Do not use `CASCADE`, drop PostgreSQL `public`, bypass migration guards, or edit an applied changeset.
- Do not start the outage or change either host until fresh target-specific authorization and all protected release approvals are recorded. The user's approval of a full outage is design input only.
- Do not delete backups until the rollback window is over and separate deletion approvals are recorded.

## Acceptance criteria

- Both hosts run only the approved replacement Ford workloads; old Ford containers and data volumes are removed only after the rollback window and cleanup approvals. No old MCP process/tunnel remains after its retirement gate.
- Service, Collector, PostgreSQL, Redis, and Ollama pass approved health and security checks.
- Active application database contains only the reviewed empty baseline and migration metadata; no prior application/catalog rows are copied.
- Scraper/scheduler remains paused. No catalog import occurs. Catalog tools remain false until a later approved data load and protected enablement.
- Reader role is least-privilege, read-only, and has no private/application-data access. Migration/runtime identities are not superuser.
- Verified backup, rollback artifacts, outage record, approvals, and cleanup/deletion evidence are retained until their separate approved disposition.

## Known blockers

The live read-only preflight found duplicate `ford`/`public` relations, separate Liquibase histories, `pgcrypto` in `public`, an overprivileged `ford` login, and broader-than-designed `ford_catalog_reader` SELECT grants. The current workspace has not proved a full `ford_service` backup/restore, production host capacity, complete deployment/secret inventory, or protected release readiness. Those items block execution. The approved catalog archive's content-hash discrepancies remain unresolved; preserve its bytes and SHA unchanged. Because this design loads no catalog data, source-hash resolution is a blocker for a later catalog ingestion/tool-enablement gate, not evidence that an empty database is populated or ready. Before any live replacement, owners must reconcile this design with the existing ordered cutover gates and explicitly approve whether the empty reset may proceed while source integrity remains blocked. No live change has been made by this design.
