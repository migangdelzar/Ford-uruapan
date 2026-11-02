# Frontend API Contracts Design

## Status

Approved by the user for contract creation on 2026-09-21.

## Goal

Provide the frontend team with a stable, versioned OpenAPI contract for the
operator application while preserving an accurate description of the backend
routes that already exist.

## Decisions

- Use a static OpenAPI 3.1.2 YAML document at
  `docs/openapi/frontend-api.yaml`.
- Use `/api/v1` for the target frontend API.
- Keep Meta webhook routes outside `/api/v1`; they are provider integration
  endpoints, not browser APIs.
- Include the currently implemented legacy handoff routes so the contract is
  useful for immediate integration.
- Mark target-only operations with `x-implementation-status: planned`.
- Use JWT bearer authentication. Roles provide coarse access; scopes provide
  operations; tenant IDs remain JWT attributes and are never accepted from
  browser-controlled headers or query parameters.
- Use RFC 9457-style `ProblemDetail` responses for API errors.
- Use cursor pagination for conversation, message, handoff, and event lists.

## Contracted Areas

- Conversations and messages
- Human handoff and advisor takeover
- Leads and CRM synchronization
- Vehicle inventory and details
- Finance estimates
- Appointment availability and booking
- Branches and on-duty advisors
- Bot configuration
- Audit/event log and event stream
- Development-only chaos/test-lab controls
- Existing Meta webhook verification and delivery

## Non-goals

This change creates contracts only. It does not implement the planned
controllers, persistence, BFF proxy, frontend application, or OpenAPI runtime
exposure.

## Compatibility Rule

The frontend must treat operations marked `planned` as unavailable until the
backend implementation is released. The BFF may use the same document for
client generation, but it must not silently turn planned operations into
browser calls to internal services.
