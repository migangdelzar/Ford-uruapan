# Database Reset Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit, confirmation-protected mise tasks for resetting the local database schema or deleting the local PostgreSQL volume.

**Architecture:** `db:reset` drops and recreates only the PostgreSQL `public` schema, preserving the Docker volume and allowing Liquibase to rebuild the schema on application startup. `db:reset-volume` removes the project’s Compose volumes and starts only PostgreSQL and Redis again. Both tasks refuse to run unless passed `--confirm`.

**Tech Stack:** Bash, mise tasks, Docker Compose, PostgreSQL, Liquibase.

## Global Constraints

- These tasks are for the local Compose project only.
- No reset task may run without the exact `--confirm` argument.
- The schema reset must preserve the named PostgreSQL volume.
- The volume reset is destructive and must remove only this Compose project’s volumes.
- The application container must not be running while the schema is reset.
- Do not run destructive reset commands during verification.

---

### Task 1: Add guarded reset tasks

**Files:**
- Create: `ford-ai-service/.mise/tasks/db/reset`
- Create: `ford-ai-service/.mise/tasks/db/reset-volume`

- [x] **Step 1: Add the schema reset task**

The task validates `--confirm`, refuses to proceed when the `ford-service`
container is running, starts PostgreSQL, and recreates only the `public`
schema. It must print the follow-up command to restart the application.

- [x] **Step 2: Add the volume reset task**

The task validates `--confirm`, runs Compose `down --volumes --remove-orphans`,
and starts PostgreSQL and Redis without starting the application.

- [x] **Step 3: Verify shell syntax without executing destructive actions**

Run:

```bash
bash -n ford-ai-service/.mise/tasks/db/reset
bash -n ford-ai-service/.mise/tasks/db/reset-volume
```

Expected: both commands exit successfully and no Docker state changes occur.

### Task 2: Document and verify the operator interface

**Files:**
- Modify: `ford-ai-service/README.md`
- Modify: `ford-ai-service/docs/operations.md`

- [x] **Step 1: Document both commands and their data-preservation behavior**
- [x] **Step 2: Verify task discovery and the clean worktree diff**

Run:

```bash
mise tasks ls
git diff --check
```

### Definition of done

- [x] `mise run db:reset` requires `--confirm`.
- [x] `mise run db:reset` preserves the PostgreSQL volume.
- [x] `mise run db:reset-volume` requires `--confirm` and removes only this project’s volumes.
- [x] Both tasks leave PostgreSQL and Redis ready for a fresh application start.
- [x] Documentation explains the distinction.
