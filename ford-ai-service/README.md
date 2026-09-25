# ford-service

MVP modular monolith for a Spanish/MX$ Ford dealership assistant in Uruapan.
It normalizes WhatsApp, Messenger and Instagram webhooks; answers catalog,
offers and financing questions; scores leads; and pauses the bot for an
operator handoff.

## Architecture decisions

- Spring Boot MVC with Java virtual threads; WebFlux is not used in the MVP.
- Spring AI only, with independent Ollama/OpenAI chat and embedding policies.
- PostgreSQL + pgvector are canonical; Redis is ephemeral.
- JDBC/`JdbcClient` is used for explicit SQL. JPA is intentionally not used.
- RAG is reserved for approved documents. Live catalog, inventory, offers,
  finance math and lead scoring are deterministic services/tools.
- Ford official feeds and authorized scraping run through scheduled ingestion,
  never from the chat request path.
- All application configuration is YAML (`application.yml` and profiles); no
  `.properties` configuration is required.

## Run locally

```bash
mise install
mise run setup
```

Mise creates `.env` when needed, generates local Grafana and operator
credentials without printing them, validates Compose, builds the Java 25
application image, starts PostgreSQL, Redis, and the minimum metrics, logging,
and tracing pipeline, and waits for their health endpoints. Docker Desktop (or
Docker Engine with Compose) must be installed and running. Host bindings default
to PostgreSQL `55432` and the API `18080` so this stack can coexist with other
local projects; set `POSTGRES_PORT` or `FORD_SERVICE_PORT` to override them.

The normal setup uses Docker for the complete JVM application and its runtime
dependencies. For a faster edit/debug loop, run the dependencies in Docker and
the Java 25 JVM on the host:

```bash
mise run deps:start
mise run dev:start
```

Grafana is optional because it is a visualization UI, not part of collection or
storage. Start the complete stack with `mise run full:start`; then open
`http://localhost:3000`.

Useful tasks, run from this directory:

```bash
mise run db:start              # PostgreSQL only
mise run deps:start            # Database, Redis, and observability
mise run app:start             # Application container
mise run full:start            # Everything, including Grafana
mise run dev:start             # Dependencies plus a host JVM
mise run check                 # Readiness checks
mise run test                  # Unit and integration tests
mise run logs                  # Follow application logs
mise run full:stop             # Stop everything, preserve volumes
mise run db:reset -- --confirm
```

Each target uses the same lifecycle verbs: `start`, `stop`, `restart`, and
`status`. `deps` contains databases, Redis, and telemetry; `app` contains the
JVM container; `full` adds Grafana; and `dev` runs the JVM on the host while
reusing Gradle's daemon and build cache. The host JVM writes its PID and log to
`.mise/state/`, which is ignored by Git.

The database reset task is deliberately guarded because it deletes the local
PostgreSQL volume. Stop tasks preserve named volumes; use Compose directly only
when you intentionally need to remove containers or volumes.

For tests, use Java 25 and run:

```bash
mise run test
```

Gradle is the JVM/build owner: its daemon and build cache are enabled, workers
are bounded to two, and the JVM heap is capped at 1 GiB. Mise owns tool
selection and service lifecycle; Docker Compose owns containers and volumes.

The integration suite uses Testcontainers. Set `TESTCONTAINERS_RYUK_DISABLED=true`
when the local Docker socket cannot mount the Ryuk helper.

Important environment variables are listed in [operations.md](docs/operations.md).
API examples and webhook contracts are in [api.md](docs/api.md).
CI controls and evidence are documented in [ci-cd.md](../docs/ci-cd.md).
Telemetry, Langfuse and dashboard operations are documented in
[observability.md](../docs/observability.md).

## OCI deployment

The remote production layout uses direct SSH and two logical roles:
`emme-1` runs the ARM64 Ford application and `emme-2` runs PostgreSQL/pgvector
and Redis over their private OCI address. Terraform is for OCI resource
adoption/replacement, Ansible configures the VMs and Docker Compose, and Mise
is the only operator-facing interface.

Copy `deployment/config/oci.env.example` to the ignored
`deployment/config/oci.env`, fill in the runtime credentials and OCI API
values, then run:

```bash
mise install
mise run deploy:secrets
mise run deploy:setup
mise run deploy:check
mise run deploy:bootstrap
mise run deploy:all
```

Read-only Terraform checks are `mise run infra:check` and
`mise run infra:plan`. Remote lifecycle tasks include
`deploy:status`, `deploy:logs`, `deploy:restart`, and `deploy:stop`; these
preserve data volumes. Database operations are `backup:run`, `backup:list`,
`backup:verify`, and the explicitly guarded `backup:restore`.

The OCI backup capability is reproducible with:

```bash
mise run infra:backup-plan
mise run infra:backup-apply
mise run deploy:backup
mise run backup:identity-check
mise run backup:run
```

The first two tasks create the protected `emme-business` bucket, the
`ford-backup-emme2` dynamic group, and its least-privilege policies. The VM
uses Instance Principal authentication; no OCI API private key is copied to
emme-2. Backup objects use the fixed prefixes `ford/backups/postgres/`,
`ford/backups/redis/`, and `ford/backups/manifests/`.

For production, set `EMME_APP_IMAGE` to the immutable GHCR image reference
published by CI (preferably `ghcr.io/...@sha256:...`) and provide a read-only
classic PAT in `GHCR_TOKEN`. The app deploy authenticates and pulls the image
on `emme-1`; if those values are absent, the local ARM64 build-and-transfer
fallback remains available.

PostgreSQL and Redis bind only to `emme-2`'s private address in the remote
Compose configuration. The public application endpoint remains behind the
existing Cloudflare Tunnel/reverse proxy; no database or Redis port should be
published publicly in OCI security lists.
