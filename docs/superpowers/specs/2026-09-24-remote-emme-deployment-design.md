# Remote EMME Deployment Design

## Goal

Deploy the production runtime to two OCI Always Free VMs from the local
development machine using Mise, direct SSH, rsync, and Docker Compose while
keeping VM addresses and credentials outside Git.

## Approved approach

Use direct SSH for the first version. Mise is the orchestration interface;
SSH and rsync are the transport layer; Docker Compose is the remote runtime.
The design must not depend on a particular IP address or cloud-specific SSH
implementation.

The deployment supports two logical machines:

- `emme-1`: Ford application and the existing Cloudflare Tunnel connector.
- `emme-2`: PostgreSQL with pgvector and Redis.

The application connects to `emme-2` through its private OCI IP. Database and
Redis ports are never published through Cloudflare and must be restricted in
OCI to traffic from `emme-1`.

## Configuration boundary

`ford-ai-service/.env.deploy` is a local, ignored operator file. The tracked
`.env.deploy.example` documents the complete interface without containing
real addresses, keys, or passwords.

The configuration separates SSH endpoints from service endpoints:

```dotenv
EMME_1_HOST=ssh-endpoint-for-app-vm
EMME_2_HOST=ssh-endpoint-for-data-vm
EMME_1_PRIVATE_IP=private-ip-of-app-vm
EMME_2_PRIVATE_IP=private-ip-of-data-vm
EMME_SSH_USER=ubuntu
EMME_SSH_KEY=/absolute/path/to/oci-emme-key
EMME_REMOTE_ROOT=/opt/ford-uruapan
```

`EMME_1_HOST` and `EMME_2_HOST` may initially be public IP addresses. Later,
they may become SSH aliases using `ProxyJump` through OCI Bastion without
changing any Mise task or Compose file.

Secrets remain in the local deployment environment and are transferred only
to the target VM environment files with restrictive permissions:

- PostgreSQL password
- Redis password
- operator password
- Meta webhook secret and verification token
- AI provider credentials

Secrets must not be printed by Mise, included in image layers, or committed.

## Runtime boundaries

The current local Compose stack remains suitable for development. Production
uses two focused Compose files:

- `deployment/runtime/data/compose.yml`: PostgreSQL/pgvector and Redis with
  persistent volumes and health checks.
- `deployment/runtime/app/compose.yml`: the prebuilt ARM64 Ford service image,
  connected to the data VM by private IP, with no local database, Redis, or
  observability dependency.

The application production Compose file binds its HTTP port to loopback on
`emme-1`. Cloudflare Tunnel publishes the API hostname to that local port, so
the application does not need a public HTTP listener.

Production does not run Prometheus, Loki, Tempo, Alloy, or Grafana on the two
Always Free VMs. OTEL export is disabled by default for this footprint. The
observability stack can be added later as an independent capability without
changing the database or app deployment contracts.

The application image is built locally for `linux/arm64`, streamed to `emme-1`
with `docker save | ssh docker load`, and started by Compose. This avoids
requiring a registry or a build toolchain on the VM.

## Mise orchestration interface

The following commands are the public deployment interface:

```text
deploy:check       Validate local tools, environment, SSH, Docker, and Compose.
deploy:bootstrap   Create remote directories and validate runtime prerequisites.
deploy:data        Upload and start PostgreSQL/Redis on emme-2.
deploy:app         Build, transfer, configure, and start the app on emme-1.
deploy:all         Bootstrap both VMs, deploy data, then deploy the app.
deploy:status      Show remote Compose status for both VMs.
deploy:logs        Follow app or data logs remotely.
deploy:restart     Restart app, data, or both by role.
deploy:stop        Stop app, data, or both without removing volumes.
```

Role selection uses an explicit argument such as `mise run deploy:logs -- app`.
All operations fail before making changes when required configuration is
missing. Remote commands use `set -euo pipefail`, and data deployment never
executes volume deletion or database reset.

## Deployment flow

1. `deploy:check` loads `.env.deploy`, validates required variables, checks
   `ssh`, `rsync`, `docker`, `docker compose`, and tests SSH connectivity.
2. `deploy:bootstrap` creates `${EMME_REMOTE_ROOT}/app` and
   `${EMME_REMOTE_ROOT}/data` with safe permissions and verifies Docker Compose
   exists remotely.
3. `deploy:data` transfers the data Compose file and its remote environment,
   starts PostgreSQL and Redis, and waits for both health checks.
4. `deploy:app` builds the ARM64 image, transfers the image and app Compose
   file, writes the app environment, starts the service, and waits for
   `/actuator/health` through the VM-local endpoint.
5. `deploy:all` verifies that the app can resolve and connect to PostgreSQL
   and Redis through `EMME_2_PRIVATE_IP`.

The remote app environment uses:

```dotenv
FORD_DATABASE_URL=jdbc:postgresql://${EMME_2_PRIVATE_IP}:5432/ford_service
FORD_REDIS_HOST=${EMME_2_PRIVATE_IP}
FORD_REDIS_PORT=6379
FORD_OTEL_METRICS_EXPORT_ENABLED=false
FORD_OTEL_SAMPLING_PROBABILITY=0
```

The exact database and AI/Meta secrets are supplied from `.env.deploy`; no
customer data or prompt content is part of deployment diagnostics.

## Failure handling and rollback

- A failed SSH, transfer, image load, health check, or Compose command stops
  the current task.
- The previous app image remains available on `emme-1` until a later cleanup
  command is explicitly introduced.
- PostgreSQL and Redis volumes are preserved by every normal lifecycle task.
- A failed app deployment does not restart or reset the data VM.
- Rollback is performed by selecting a previously transferred image tag in the
  remote app environment and restarting only the app service.

## Validation

Local checks:

- shell syntax for every deployment task and helper
- Compose config validation for both production files
- `.env.deploy.example` variable coverage
- ARM64 image build/load validation

Remote checks:

- SSH and Docker prerequisite checks on both VMs
- PostgreSQL and Redis health checks on `emme-2`
- app health check on `emme-1`
- private-IP TCP connectivity from app VM to data VM
- public Cloudflare API health check and Meta webhook signature rejection

## Alternatives considered

### Ansible

Ansible would provide richer inventory, idempotent package installation, and
rolling deployment primitives. It adds another tool and inventory model for a
two-VM deployment, so it is deferred until the topology grows.

### OCI Bastion first

Bastion is the stronger production access model and should be adopted when the
VMs no longer need public SSH endpoints. Direct SSH is selected now for speed;
SSH aliases and the Mise transport boundary preserve a clean migration path.

### Terraform or OCI Resource Manager

Infrastructure-as-code is valuable for creating VCNs, VMs, security rules, and
volumes. It is outside this runtime deployment change and can be added later
without changing the Mise deployment commands.
