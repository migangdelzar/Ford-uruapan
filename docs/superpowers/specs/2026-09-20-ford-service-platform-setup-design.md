# Ford Service Cross-Platform Setup Architecture

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | Proposed for written review |
| Branch | `feat/ford-service-mvp` |
| Scope | Host prerequisites, rootless Docker on Linux, Colima on macOS, and repository setup under `ford-ai-service/` |

## Goal

Make a fresh Ford Service checkout easy to prepare on supported developer
hosts without hiding platform-specific behavior or requiring developers to
memorize package-manager and Docker commands.

The common project interface will be mise tasks:

```bash
mise run setup:check
mise run setup:host
mise run setup:host -- --apply
mise run setup:docker -- --apply
mise run setup:env
mise run doctor
```

The `setup` namespace is the technical task name. Documentation will use
“Onboarding” as the human-facing section name.

## Scope and non-goals

This design supports:

- Fedora and Oracle Linux hosts using `dnf`;
- macOS hosts using Homebrew and the existing Colima `emme` profile;
- rootless Docker Engine on Fedora and Oracle Linux;
- Docker Compose, Buildx, Gradle/Testcontainers, Java 25, and Node-based
  repository scripts;
- safe local environment-file initialization.

This design does not:

- support rootful Docker on Linux;
- install or configure Docker Desktop on macOS;
- replace the existing application Compose topology;
- provision production infrastructure or production secrets;
- delete Docker volumes, Colima profiles, packages, or existing env files;
- attempt to make Colima’s Docker daemon rootless inside its VM. macOS is
  treated as root on the isolated Colima VM, while avoiding a privileged
  Docker daemon on the macOS host.

## Architecture

Existing application and capability tasks remain unchanged. Host preparation
is added as a separate capability:

```text
mise task
  |
  +--> setup:check      read-only diagnostics
  +--> setup:host       package and host prerequisite adapter
  +--> setup:docker     runtime-specific Docker adapter
  +--> setup:env        non-destructive local env initialization
  +--> doctor           compatibility alias for setup:check
        |
        +--> shared setup helpers
              +--> macOS: Homebrew + Colima
              +--> Linux: dnf + rootless Docker + systemd user service
```

The mise tasks are a stable UX layer. Platform-specific shell adapters live
under `scripts/setup/` so they can be tested independently and kept small.
They will use `MISE_PROJECT_ROOT` when invoked by mise and derive the
repository root without assuming the caller’s working directory.

The first mise installation is necessarily documented as a platform-specific
bootstrap step because a mise task cannot run before mise exists. Once mise is
available, all repository setup uses the grouped task interface.

## Task contract

### `setup:check`

Read-only checks report actionable results for:

- supported operating system and distribution;
- mise, Java, Node, Git, and Gradle wrapper availability;
- Docker CLI and Compose availability;
- Docker daemon connectivity and active context;
- Linux rootless prerequisites and user service state;
- macOS Colima installation, profile state, active context, and profile
  socket;
- the presence of `.env` without printing its contents.

The task returns non-zero when required tooling is unavailable and identifies
the corresponding `setup:host`, `setup:docker`, or documentation action.

### `setup:host`

The default mode is a dry-run that reports intended actions. `--apply` is
required for package installation, repository configuration, service setup,
or other host mutations.

On Linux it delegates to the `dnf` adapter. On macOS it delegates to the
Homebrew/Colima adapter. It is idempotent and skips already-satisfied
requirements.

### `setup:docker`

This task isolates container-runtime setup from general package setup. It uses
the same dry-run and explicit `--apply` contract, and verifies the runtime
after any requested changes.

### `setup:env`

This task creates `.env` from `.env.example` only when `.env` does not exist.
It uses restrictive file creation permissions, refuses to overwrite an
existing file, and never displays or modifies secret values. Production env
files remain externally managed and are not created by this task.

### `doctor`

The existing `doctor` command remains available as a compatibility alias for
the expanded read-only setup diagnostics. Existing application task names and
their behavior remain stable.

## Linux adapter: Fedora and Oracle Linux

The adapter will detect the distribution from `/etc/os-release` and accept
Fedora and Oracle Linux identifiers. Unsupported `dnf` distributions fail
with a message explaining the supported platform contract rather than
guessing package or repository names.

The applied package set will cover:

- Git, curl, certificates, and the DNF repository-management utilities;
- Docker Engine client/runtime packages, Compose plugin, Buildx plugin, and
  rootless extras;
- rootless prerequisites such as `uidmap` and the user-session/networking
  dependencies required by the detected distribution;
- repository tooling needed to install the supported Docker packages.

The implementation will use the current official Docker repository guidance
for the detected RPM family and will keep the package list in one adapter
constant. It will not silently substitute Podman for Docker because the
project’s Compose and Testcontainers contract is Docker-based.

Rootless configuration will:

1. verify subordinate UID/GID mappings and report what is missing;
2. ensure a rootful Docker service is not active, failing with migration
   instructions instead of stopping or deleting it automatically;
3. run Docker’s rootless setup tool as the current user;
4. enable and start the per-user Docker systemd service;
5. optionally enable login linger so the user service can persist across
   logout/reboot;
6. select the rootless Docker context;
7. verify Compose, Buildx, and `docker info` without requiring Docker group
   membership.

No Linux setup step adds the user to the `docker` group. The documentation
will explain that rootless mode reduces daemon/container privilege and that
its networking, port-binding, and resource-limit behavior differs from
rootful Docker.

## macOS adapter: Homebrew and Colima

The adapter will check or install, through Homebrew:

- mise;
- Docker CLI and Compose;
- Colima.

The Docker runtime uses the existing `emme` Colima profile. Applied setup will
start an existing profile or create it with the Docker runtime when absent. It
will not delete or recreate an existing profile when its configuration is
incompatible; it will report the required manual action instead.

The adapter will select the Colima Docker context and verify the profile’s
socket and daemon. Colima’s Docker daemon runs as root inside its isolated
Linux VM; this is an explicit macOS platform choice and is documented rather
than presented as Linux-style rootless Docker.

## Docker socket and Testcontainers integration

The current Gradle configuration hardcodes a Colima-compatible
`/var/run/docker.sock` override. That is insufficient for Linux rootless
Docker, whose daemon socket is user-scoped.

A shared runtime helper will derive the active endpoint and expose the
appropriate values to local commands and Gradle:

- Linux rootless Docker uses the current user runtime directory and rootless
  context/socket;
- macOS Colima uses the active `emme` profile socket and the existing
  Testcontainers-in-VM override behavior.

The helper will avoid leaking raw environment values. Docker Compose tasks,
Gradle test tasks, and diagnostics will consume the same runtime detection
logic so they cannot silently target different daemons.

The implementation will retain the existing Testcontainers behavior for
Colima and add rootless Linux coverage. If a runtime cannot expose a socket
that Testcontainers can use, `setup:check` will identify that limitation
before integration tests are attempted.

## Environment and security behavior

The existing mise environment model remains authoritative:

- development is selected by default through `.miserc.toml`;
- production remains explicit through `mise -E prod`;
- `.env` is local and ignored;
- production values come from the caller, CI, or an external secret manager;
- setup tasks do not read or print secret values.

`setup:env` is the only task that may create a local `.env`, and it only does
so when absent. `env:check` and `env:list` continue to report presence and
variable names without displaying values.

## Error handling and safety

All adapters will use strict shell behavior and return non-zero on failed
prerequisites or commands. Errors will include:

- detected platform and runtime;
- the failed command category;
- a safe next action;
- whether the issue requires rerunning with `--apply`, a new login session,
  or manual migration.

They will not use destructive commands such as Docker volume deletion,
Colima profile deletion, package removal, rootful-service shutdown, or env-file
replacement as an implicit repair step.

## Documentation and verification

Update the service README and operations documentation with:

- mise installation commands for Fedora/Oracle Linux and macOS;
- the common setup task flow and dry-run/apply semantics;
- rootless Docker behavior and the Linux security model;
- macOS Colima VM-root boundary;
- `emme` profile requirements and recovery guidance;
- environment initialization and production-env rules;
- troubleshooting for Docker sockets, user services, subordinate IDs, and
  Testcontainers.

Verification will include:

- shell syntax checks for all adapters and helpers;
- contract tests for task names, dry-run safety, platform dispatch, package
  sets, rootless setup behavior, and secret non-disclosure;
- mocked-command tests for `dnf`, `brew`, `systemctl`, `loginctl`, and
  `colima` paths;
- existing mise task validation and environment checks;
- Docker Compose configuration checks;
- Java unit tests and integration tests where a Docker runtime is available;
- documentation command examples reviewed against the implemented task
  contract.

## Acceptance criteria

The work is complete when:

1. `mise run setup:check` gives actionable results on macOS, Fedora, and
   Oracle Linux.
2. Linux setup provisions rootless Docker without Docker group membership or
   an active rootful Docker service.
3. macOS setup uses Homebrew and the `emme` Colima Docker profile without
   Docker Desktop.
4. Setup defaults to dry-run and requires `--apply` for host mutations.
5. Existing Java, Node, Compose, Gradle, Testcontainers, and mise task flows
   use the detected runtime consistently.
6. `.env` setup never overwrites an existing file or exposes credentials.
7. The README and operations guide describe the complete fresh-checkout flow.
8. Automated contract and syntax tests pass, with environment-dependent
   integration results reported separately when no Docker daemon is available.
