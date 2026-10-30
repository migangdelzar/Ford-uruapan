# Preflight DB Probe Independence

## Goal

Ensure read-only primary and catalog database TCP probes run for each Service slot even when Docker log retrieval fails.

## Design

Run the existing DB endpoint extraction and TCP checks before attempting the optional sanitized log scan. Keep output limited to fixed probe labels and states; never print JDBC URLs, hostnames, credentials, or raw logs. Log scan failures remain diagnostic and must not suppress DB connectivity evidence. Existing health, collector, and PostgreSQL gates remain unchanged.

## Test

Extend the preflight contract test to assert DB probe logic precedes log retrieval. Run focused and full Python contract tests, parse embedded Python and workflow YAML, and run `git diff --check`.

## Scope

No production commands, DB queries/writes, deployment, restart, routing, or collector changes.
