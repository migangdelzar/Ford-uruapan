# Ford Service Cross-Platform Setup Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add safe, cross-platform setup tasks for rootless Docker on Fedora/Oracle Linux and Colima on macOS while keeping the existing mise task interface stable.

**Architecture:** Mise tasks under ford-ai-service/.mise/tasks/setup/ provide the common interface. Focused shell adapters under scripts/setup/ perform platform-specific checks and explicitly gated mutations. A shared runtime helper derives Docker endpoints for Compose and Testcontainers so Linux rootless sockets and macOS Colima sockets do not depend on one hardcoded path.

**Tech Stack:** Bash, Node.js built-in test runner, mise executable tasks, Docker Engine rootless mode, Docker Compose plugin, Buildx, Homebrew, Colima, systemd user services, Gradle/Testcontainers, Markdown.

## Global Constraints

- Fedora and Oracle Linux are the supported dnf distributions; unsupported distributions fail clearly.
- Linux Docker is rootless; no user is added to the docker group.
- macOS uses Homebrew and the existing emme Colima Docker profile; Docker may run as root inside the isolated Colima VM.
- Setup commands are dry-run by default; --apply is required for host mutations.
- Do not remove packages, stop rootful services automatically, delete Docker data, delete Colima profiles, overwrite env files, or print secrets.
- Java 25 and Node used by repository scripts remain project-managed through mise.
- Production remains explicit through mise -E prod.
- Existing app:*, test:*, docker:*, observability:*, env:*, and load:* tasks remain usable.
- Use MISE_PROJECT_ROOT for service-root paths.
- Use current official Docker and Colima guidance for repository and rootless setup details.

---

## File Map

Create:

- scripts/setup/common.sh — strict shell utilities, dry-run gating, OS detection, and command reporting.
- scripts/setup/runtime.sh — Docker endpoint and Testcontainers environment derivation.
- scripts/setup/dnf.sh — Fedora/Oracle Linux package and rootless Docker adapter.
- scripts/setup/macos.sh — Homebrew and Colima adapter for the emme profile.
- ford-ai-service/.mise/tasks/setup/check — read-only setup diagnostics.
- ford-ai-service/.mise/tasks/setup/host — platform setup dispatcher.
- ford-ai-service/.mise/tasks/setup/docker — Docker runtime dispatcher.
- ford-ai-service/.mise/tasks/setup/env — safe .env initialization.
- scripts/setup-contract.test.mjs — static and mocked-command setup tests.

Modify:

- ford-ai-service/.mise/tasks/doctor — delegate to setup/check.
- ford-ai-service/.mise/lib/compose.sh — source runtime setup before Compose commands.
- ford-ai-service/.mise/tasks/test/unit — export runtime variables before Gradle.
- ford-ai-service/.mise/tasks/test/integration — export runtime variables before Gradle.
- ford-ai-service/.mise/tasks/test/check — export runtime variables before Gradle.
- ford-ai-service/.mise/tasks/test/ai-evaluation — export runtime variables before Gradle.
- ford-ai-service/mise.toml — add the pinned Node tool.
- ford-ai-service/build.gradle.kts — consume an externally derived Testcontainers socket override.
- .github/workflows/reusable-java-ci.yml — provide the standard Linux socket to integration CI.
- scripts/mise-contract.test.mjs — assert setup tasks, runtime usage, Node management, and docs.
- ford-ai-service/README.md, ford-ai-service/docs/operations.md, and docs/ci-cd.md — document complete onboarding.

---

### Task 1: Add failing setup contracts

**Files:**

- Create: scripts/setup-contract.test.mjs
- Modify: scripts/mise-contract.test.mjs

**Interfaces:**

- Consumes: approved setup task names and adapter behavior.
- Produces: tests that fail until setup tasks, adapters, runtime helper, and docs exist.

- [ ] Step 1: Add tests for executable tasks and scripts.

Assert that these executable tasks exist and reference MISE_PROJECT_ROOT:

    setup/check
    setup/host
    setup/docker
    setup/env

Assert that these executable scripts exist:

    scripts/setup/common.sh
    scripts/setup/runtime.sh
    scripts/setup/dnf.sh
    scripts/setup/macos.sh

Assert that task sources contain argument forwarding and that adapter sources contain dry-run handling.

- [ ] Step 2: Add safety assertions.

Assert that tracked setup sources contain no docker group addition, dnf removal, Docker volume deletion, or Colima profile deletion. Assert Linux distribution checks for Fedora and Oracle Linux, rootless setup commands, user systemd commands, Homebrew, and the emme profile.

Assert runtime source derives DOCKER_HOST and TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE rather than using one global hardcoded socket.

- [ ] Step 3: Add env initialization behavior tests.

Run the eventual setup/env script against a temporary mock project root. Assert the first invocation creates .env with mode 0600, the second invocation fails, and the original file is unchanged.

- [ ] Step 4: Run the focused tests and confirm they fail.

    node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs

Expected: failure because the new files do not exist.

- [ ] Step 5: Commit the contracts.

    git add scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs
    git commit -m "test: define cross-platform setup contracts"

---

### Task 2: Implement common helpers and runtime detection

**Files:**

- Create: scripts/setup/common.sh
- Create: scripts/setup/runtime.sh
- Test: scripts/setup-contract.test.mjs

**Interfaces:**

- Consumes: SETUP_APPLY, MISE_PROJECT_ROOT, FORD_COLIMA_PROFILE, DOCKER_HOST, and host commands.
- Produces: setup_is_apply, setup_run, setup_require_command, setup_os_id, setup_project_root, ford_runtime_export, and ford_runtime_status.

- [ ] Step 1: Add controlled helper tests.

Use a temporary mock PATH and execute only helper functions. Assert default dry-run and explicit apply behavior. Assert OS ID parsing from a mocked os-release file. Assert Linux rootless derivation produces a runtime socket under XDG_RUNTIME_DIR.

The harness must not invoke package managers, systemd, Docker, Homebrew, or Colima.

- [ ] Step 2: Implement scripts/setup/common.sh.

Use strict Bash behavior. Implement:

    setup_is_apply() {
      [[ "\${SETUP_APPLY:-0}" == "1" || "\${1:-}" == "--apply" ]]
    }

    setup_run() {
      if setup_is_apply; then
        "$@"
      else
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
      fi
    }

    setup_require_command() {
      command -v "$1" >/dev/null 2>&1 || {
        printf 'Missing required command: %s\n' "$1" >&2
        return 1
      }
    }

    setup_os_id() {
      awk -F= '$1 == "ID" {gsub(/"/, "", $2); print tolower($2)}' /etc/os-release
    }

Log command names and non-secret arguments only. Do not create a generic privilege wrapper that hides which command mutates the host.

- [ ] Step 3: Implement scripts/setup/runtime.sh.

Implement ford_runtime_export with this order:

1. Preserve existing DOCKER_HOST and derive only missing Testcontainers settings.
2. On macOS, use FORD_COLIMA_PROFILE or emme and derive the profile socket under HOME/.colima/profile/docker.sock. Set the Colima Testcontainers override to /var/run/docker.sock.
3. On Linux, when the active context is rootless or the user runtime socket exists, derive unix://XDG_RUNTIME_DIR/docker.sock and use that filesystem path for the Testcontainers override.
4. Otherwise preserve normal Docker CLI defaults for rootful CI or externally managed runtimes.

Implement ford_runtime_status to print platform, context, and socket path only.

- [ ] Step 4: Run syntax and helper tests.

    bash -n scripts/setup/common.sh scripts/setup/runtime.sh
    node --test scripts/setup-contract.test.mjs

Expected: helper tests pass; adapter and task existence checks remain red.

- [ ] Step 5: Commit shared helpers.

    git add scripts/setup/common.sh scripts/setup/runtime.sh scripts/setup-contract.test.mjs
    git commit -m "feat: add cross-platform setup helpers"

---

### Task 3: Implement the Fedora/Oracle Linux rootless dnf adapter

**Files:**

- Create: scripts/setup/dnf.sh
- Modify: scripts/setup/common.sh
- Test: scripts/setup-contract.test.mjs

**Interfaces:**

- Consumes: --apply, SETUP_APPLY, /etc/os-release, dnf, sudo, systemctl, loginctl, and Docker rootless setup tooling.
- Produces: dnf_check, dnf_setup_host, dnf_setup_docker, and dnf_check_rootless.

- [ ] Step 1: Add mocked adapter tests.

Use a temporary mock bin that records arguments. Dry-run must not execute the package manager or service commands. The planned package set must include Docker Engine/CLI, Compose, Buildx, rootless extras, uidmap, and the detected distribution’s user-session dependencies.

Apply-mode tests must assert:

- the correct official Docker RPM repository path is selected for Fedora or Oracle Linux;
- dockerd-rootless-setuptool.sh install runs as the current user;
- systemctl --user enable --now docker.service is used;
- loginctl enable-linger is only run in apply mode;
- an active rootful docker.service causes an actionable failure;
- no group add, package removal, profile deletion, or volume deletion command is used.

Mock dnf, sudo, systemctl, loginctl, docker, and dockerd-rootless-setuptool.sh so tests run on macOS.

- [ ] Step 2: Implement distribution detection and package planning.

Accept Fedora and Oracle Linux identifiers from /etc/os-release, including Oracle Linux variants documented by the platform. Return a clear unsupported-distribution error for all other IDs.

Keep the package list in one array. Use the current official Docker RPM repository instructions for the detected family. Run dnf install -y only through setup_run.

- [ ] Step 3: Implement rootless prerequisite checks.

Check that newuidmap/newgidmap are available and that the current user has subordinate UID and GID ranges. Do not edit /etc/subuid or /etc/subgid automatically; report the administrator action when missing.

Reject rootless setup when running as root. Detect an active rootful docker.service and fail with migration instructions instead of stopping or deleting it.

- [ ] Step 4: Implement rootless service setup.

Run Docker’s rootless setup tool as the developer user, enable the per-user service, select the rootless context, and verify docker info reports rootless security options. Do not add Docker group membership.

- [ ] Step 5: Run tests and syntax checks.

    bash -n scripts/setup/dnf.sh
    node --test scripts/setup-contract.test.mjs

Expected: all DNF mock tests pass without requiring a Linux host.

- [ ] Step 6: Commit the Linux adapter.

    git add scripts/setup/dnf.sh scripts/setup/common.sh scripts/setup-contract.test.mjs
    git commit -m "feat: add rootless dnf Docker setup"

---

### Task 4: Implement the macOS Homebrew/Colima adapter

**Files:**

- Create: scripts/setup/macos.sh
- Test: scripts/setup-contract.test.mjs

**Interfaces:**

- Consumes: --apply, SETUP_APPLY, brew, colima, docker, and FORD_COLIMA_PROFILE.
- Produces: macos_check, macos_setup_host, and macos_setup_docker.

- [ ] Step 1: Add mocked adapter tests.

Assert dry-run and apply behavior for Homebrew formulas mise, docker, docker-compose, and colima. Assert default profile emme. Assert profile-aware Docker runtime startup and post-start context/socket checks. Assert no path invokes colima delete, colima delete --data, or profile recreation.

- [ ] Step 2: Implement Homebrew checks and installation.

macos_check reports missing formulas without installing. macos_setup_host uses setup_run brew install and skips already installed formulas.

- [ ] Step 3: Implement profile-safe Colima setup.

macos_setup_docker must:

1. verify Colima exists;
2. inspect emme state;
3. start an absent or stopped profile with Docker runtime;
4. refuse to recreate an incompatible existing profile;
5. select the Colima Docker context;
6. wait with a bounded retry loop for docker info;
7. verify Compose, Buildx, and the expected profile socket.

Output must state that Docker runs as root inside the isolated Colima VM and that this is expected on macOS.

- [ ] Step 4: Run tests and syntax checks.

    bash -n scripts/setup/macos.sh
    node --test scripts/setup-contract.test.mjs

- [ ] Step 5: Commit the macOS adapter.

    git add scripts/setup/macos.sh scripts/setup-contract.test.mjs
    git commit -m "feat: add macos Colima setup"

---

### Task 5: Add mise setup tasks and diagnostics

**Files:**

- Create: ford-ai-service/.mise/tasks/setup/check
- Create: ford-ai-service/.mise/tasks/setup/host
- Create: ford-ai-service/.mise/tasks/setup/docker
- Create: ford-ai-service/.mise/tasks/setup/env
- Modify: ford-ai-service/.mise/tasks/doctor
- Modify: ford-ai-service/mise.toml
- Test: scripts/setup-contract.test.mjs and scripts/mise-contract.test.mjs

**Interfaces:**

- Consumes: MISE_PROJECT_ROOT, setup adapters, --apply, and the selected mise environment.
- Produces: setup:check, setup:host, setup:docker, setup:env, and the existing doctor command.

- [ ] Step 1: Add task behavior tests.

Assert setup/check is read-only, host and Docker tasks forward arguments without implying apply, env setup refuses overwrite, doctor delegates to setup/check, and mise.toml manages Java 25 and Node 22.

- [ ] Step 2: Implement setup/check.

Source common and runtime helpers. Detect the platform, call its read-only adapter checks, check mise/Java/Node/Gradle, report Docker context/socket status, and never call package managers, systemd mutation commands, colima start, or other mutating commands.

- [ ] Step 3: Implement setup/host and setup/docker.

Dispatch macOS by uname -s and Linux by /etc/os-release. Reject unsupported hosts. Pass --apply only when it appears in task arguments.

- [ ] Step 4: Implement setup/env.

Create .env from .env.example only when .env is absent, using restrictive mode 0600 and a temporary file. Fail before writing if .env already exists. Do not read .env contents.

- [ ] Step 5: Add Node 22 and preserve doctor.

Update mise.toml to:

    [tools]
    java = "25"
    node = "22"

Replace the old doctor body with an exec delegation to setup/check.

- [ ] Step 6: Validate.

    node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs
    (cd ford-ai-service && mise tasks validate)

- [ ] Step 7: Commit the mise interface.

    git add ford-ai-service/.mise/tasks/setup ford-ai-service/.mise/tasks/doctor ford-ai-service/mise.toml scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs
    git commit -m "feat: add grouped setup mise tasks"

---

### Task 6: Integrate runtime sockets with Compose and Testcontainers

**Files:**

- Modify: ford-ai-service/.mise/lib/compose.sh
- Modify: scripts/setup/runtime.sh so service-local tasks can source it through a stable repository-relative path
- Modify: ford-ai-service/.mise/tasks/test/unit
- Modify: ford-ai-service/.mise/tasks/test/integration
- Modify: ford-ai-service/.mise/tasks/test/check
- Modify: ford-ai-service/.mise/tasks/test/ai-evaluation
- Modify: ford-ai-service/build.gradle.kts
- Modify: .github/workflows/reusable-java-ci.yml
- Test: setup, mise, and workflow contract tests

**Interfaces:**

- Consumes: ford_runtime_export.
- Produces: one Docker endpoint contract for Compose, Gradle, and Testcontainers.

- [ ] Step 1: Add failing integration assertions.

Assert every Compose task sources the runtime helper through compose.sh, every Gradle-backed mise test task exports runtime variables before Gradle, Gradle no longer unconditionally sets /var/run/docker.sock, CI explicitly supplies the standard Linux socket, and helper tests cover rootless Linux and Colima.

- [ ] Step 2: Integrate Compose.

Source runtime.sh from compose.sh, call ford_runtime_export before docker compose or docker-compose, and preserve the existing Compose v2/standalone fallback and arguments.

- [ ] Step 3: Integrate Gradle task wrappers.

Source the helper in each test task and call ford_runtime_export immediately before the existing exec ./gradlew command. Do not duplicate platform branches in individual tasks.

- [ ] Step 4: Make Gradle consume external configuration.

Change build.gradle.kts so the Testcontainers override is applied only when supplied by the process environment. Add TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock to the reusable CI integration job. Leave unit and evaluation jobs unchanged unless their tests require the setting.

- [ ] Step 5: Run focused verification.

    node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs scripts/workflow-contract.test.mjs

- [ ] Step 6: Commit runtime integration.

    git add ford-ai-service/.mise/lib ford-ai-service/.mise/tasks/test ford-ai-service/build.gradle.kts .github/workflows/reusable-java-ci.yml scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs scripts/workflow-contract.test.mjs
    git commit -m "feat: support rootless Docker runtime sockets"

---

### Task 7: Update onboarding and operations documentation

**Files:**

- Modify: ford-ai-service/README.md
- Modify: ford-ai-service/docs/operations.md
- Modify: docs/ci-cd.md
- Test: scripts/setup-contract.test.mjs

**Interfaces:**

- Consumes: final task names, adapter output, and runtime/socket behavior.
- Produces: copy-pasteable fresh-checkout instructions for macOS and Fedora/Oracle Linux.

- [ ] Step 1: Add documentation assertions.

Assert docs contain mise installation guidance for macOS and Fedora/Oracle Linux, setup/check, dry-run setup/host, explicit --apply, setup/docker, setup/env, rootless Linux language, no Docker group requirement, emme profile, macOS VM-root boundary, and explicit production selection.

- [ ] Step 2: Update README.

Document:

    mise trust
    mise run setup:check
    mise run setup:host
    mise run setup:host -- --apply
    mise run setup:docker -- --apply
    mise run setup:env
    mise run doctor

Add initial mise installation commands, new-login guidance for Linux user services, and the macOS Colima-not-Docker-Desktop rule.

- [ ] Step 3: Update operations and CI docs.

Document subordinate UID/GID requirements, rootless user services, active Docker contexts, profile socket diagnostics, Testcontainers behavior, non-destructive troubleshooting, and CI’s standard rootful runner socket.

- [ ] Step 4: Run documentation contracts.

    node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs

- [ ] Step 5: Commit docs.

    git add ford-ai-service/README.md ford-ai-service/docs/operations.md docs/ci-cd.md scripts/setup-contract.test.mjs
    git commit -m "docs: document cross-platform service setup"

---

### Task 8: Complete verification matrix

**Files:**

- Modify: only files required to fix verified failures.
- Test: all existing and new contract tests.

- [ ] Step 1: Run static, syntax, and contract checks.

    git diff --check
    bash -n scripts/setup/*.sh ford-ai-service/.mise/tasks/setup/* ford-ai-service/.mise/lib/*.sh
    node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs scripts/workflow-contract.test.mjs scripts/operations-contract.test.mjs scripts/observability-contract.test.mjs
    (cd ford-ai-service && mise tasks validate)

Expected: all tests pass and all setup/task scripts parse.

- [ ] Step 2: Verify current mise, environment, and Compose behavior.

    (cd ford-ai-service && mise run setup:check)
    (cd ford-ai-service && mise run env:check)
    (cd ford-ai-service && mise run docker:config)
    docker-compose --env-file ford-ai-service/deployment/observability/langfuse/.env.example -f ford-ai-service/deployment/observability/langfuse/docker-compose.yml config --quiet

Expected: diagnostics accurately report the current host; environment and Compose checks pass when prerequisites are available.

- [ ] Step 3: Verify production overlay safety.

    FORD_DATABASE_URL='jdbc:postgresql://prod.example.invalid:5432/ford_service' FORD_DATABASE_USERNAME='ford_service' FORD_DATABASE_PASSWORD='test-only-value' sh -c 'cd ford-ai-service && mise -E prod run env:check'

Expected: production validation passes without printing the password.

- [ ] Step 4: Run Java verification.

    (cd ford-ai-service && mise run test:unit)
    (cd ford-ai-service && mise run test:check)

Expected: unit tests pass. Integration tests pass when rootless Docker or Colima is available; otherwise failure identifies daemon availability rather than a code regression.

- [ ] Step 5: Review final state.

    git status --short
    git diff --stat
    git log -8 --oneline --decorate

Commit only verified fixes with a focused message.
