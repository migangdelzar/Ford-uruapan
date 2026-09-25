# ford-service

MVP modular monolith for a Spanish/MX$ Ford dealership assistant in Uruapan.
It normalizes WhatsApp, Messenger and Instagram webhooks; answers catalog,
offers and financing questions; scores leads; and pauses the bot for an
operator handoff.

## Architecture decisions

- Spring Boot MVC with Java virtual threads; WebFlux is not used in the MVP.
- Spring AI only, with independent Ollama/OpenAI chat and embedding policies.
- PostgreSQL + pgvector are canonical; Redis is ephemeral.
- Conversation transcript memory is durable in PostgreSQL and scoped by tenant,
  channel, provider account, and participant. Optional semantic fact retrieval
  is controlled by `FORD_MEMORY_SEMANTIC_ENABLED`.
- JDBC/`JdbcClient` is used for explicit SQL. JPA is intentionally not used.
- RAG is reserved for approved documents. Live catalog, inventory, offers,
  finance math and lead scoring are deterministic services/tools.
- Ford official feeds and authorized scraping run through scheduled ingestion,
  never from the chat request path.
- All application configuration is YAML (`application.yml` and profiles); no
  `.properties` configuration is required.

## Local development and onboarding

Install mise once before running project tasks. The official installer works
on macOS, Fedora, and Oracle Linux:

```bash
curl -fsSL https://mise.run | sh
echo 'eval "$(~/.local/bin/mise activate zsh)"' >> ~/.zshrc
```

Restart the shell, then from this directory trust the project and inspect the
host requirements:

```bash
mise trust
mise run setup:check
```

On macOS, setup uses Homebrew and the existing `emme` Colima profile. On
Fedora or Oracle Linux, preview the DNF and rootless Docker changes first:

```bash
mise run setup:host
mise run setup:host -- --apply
mise run setup:docker -- --apply
```

Linux setup uses rootless Docker and never adds the user to the `docker` group.
Docker runs as root inside Colima’s isolated Linux VM on macOS; Docker Desktop
is not required. A new Linux login session may be required after enabling the
user Docker service.

Create the local environment file without overwriting an existing one:

```bash
mise run setup:env
mise run doctor
```

The service defaults to the `dev` mise environment through `.miserc.toml`.
Useful grouped tasks:

```bash
mise run app:run
mise run docker:deps:up
mise run docker:deps:ps
mise run docker:deps:down
mise run db:reset -- --confirm
mise run db:reset-volume -- --confirm
mise run test:unit
mise run test:integration
mise run test:check
mise run docker:up
mise run docker:logs
mise run observability:up
mise run load:gate
```

For host-JVM development, start PostgreSQL and Redis in Compose, then run
Spring Boot directly with the mise-selected Java version:

```bash
mise run docker:deps:up
mise run app:run
```

For the fully containerized mode, build and start PostgreSQL, Redis, and
Spring Boot together:

```bash
mise run docker:up
```

To start with a clean local database, stop the Spring application first and
choose one of these explicit reset tasks:

```bash
# Recreate the PostgreSQL schema but preserve the Docker volume.
mise run db:reset -- --confirm

# Delete the project PostgreSQL volume and recreate PostgreSQL/Redis.
mise run db:reset-volume -- --confirm
```

Both commands delete application data, including Meta channel bindings. Start
the application afterward so Liquibase recreates the schema. In the default
`dev` profile, a numeric `FORD_META_PHONE_ID` automatically recreates the
default WhatsApp binding. Set `FORD_META_AUTO_BINDING_ENABLED=false` when
managing bindings manually or when using multiple numbers; the volume reset is
more destructive, and neither command touches unrelated Docker projects.

Inspect available tasks with `mise tasks ls`.

For production-like validation, select the production overlay explicitly and
provide its required variables through the shell, CI, or a secret manager:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

The bot-only test mode is explicit and reversible:

```bash
FORD_HUMAN_HANDOFF_ENABLED=false mise run app:run
```

With this setting, provider failures and assistant escalation requests are
returned as bot fallback messages. They do not create `handoff_ticket` rows or
publish `HandoffRequested`; successful fallback turns use the normal outbound
delivery path. Production keeps handoff enabled unless the deployment
explicitly overrides it.

Durable conversation memory is enabled by default. Tune its bounded context
with the `FORD_MEMORY_*` variables in `.env.example`. Keep semantic retrieval
disabled until the embedding model and pgvector dimensions have been verified.

Optional TypeSafe Jev classification is configured with `TYPESAFE_API_KEY` and
the `FORD_AI_CLASSIFICATION_*` variables. It falls back to deterministic rules
when unavailable. Keep `FORD_AI_GUARDRAILS_ENABLED` and
`FORD_AI_SELF_REFINE_ENABLED` disabled until the base bot flow is verified;
both add provider calls and latency.

Do not commit `.env`, `.env.prod`, or any provider credentials. Use
`.env.prod.example` only as a variable-name template.

The integration suite uses Testcontainers. Setup derives the Docker socket for
rootless Linux or the `emme` Colima profile. If Colima is unavailable, run
`mise run setup:check` and use `ford-colima-recover` only when its diagnostics
indicate the profile needs recovery.

Important environment variables are listed in [operations.md](docs/operations.md),
and the complete external integration checklist is in
[integration-setup.md](docs/integration-setup.md). Start from
[.env.example](.env.example) for local configuration.
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
