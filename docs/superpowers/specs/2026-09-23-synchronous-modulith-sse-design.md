# Synchronous Modulith Events and MVC SSE

## Goal

Add a reusable event path for the CRM UI without adding WebFlux or Spring AI token streaming.

## Design

Inbound channel messages are persisted first and then published as a Spring application event. Spring Modulith's JDBC event publication registry tracks transactional listener delivery. A realtime listener maps internal events to a small frontend event envelope and publishes them to an in-process, tenant-scoped hub. The MVC controller exposes the hub through `SseEmitter`.

The durable database message remains the source of truth. SSE is only a notification channel: a reconnecting client must refetch conversation state. The first integrated event is `message.created` for accepted inbound messages. Delivery status, handoff, conversation-state, and assistant lifecycle events use the same envelope and hub once their existing application services emit them.

Spring AI remains synchronous. The existing `ChatClient` adapter produces a complete response, which is persisted and queued through the existing outbox before provider delivery. No `spring-boot-starter-webflux`, `ChatClient.stream()`, or per-token Modulith events are introduced.

## Event contracts

Internal events are typed records and are not exposed directly to the browser. Frontend events contain a generated event ID, tenant ID, aggregate ID, event type, occurrence time, and sanitized data. The hub filters subscriptions by tenant ID and removes failed or closed emitters.

## Failure handling

- Persistence occurs before `InboundMessageAccepted` is published.
- A duplicate provider message publishes no second event.
- Failed SSE delivery removes the subscriber; the client reconnects and refetches authoritative data.
- Spring Modulith's JDBC registry provides durable retry tracking for transactional event listeners.
- Provider delivery remains the responsibility of the existing JDBC outbox and retry/dead-letter flow.

## Security and operations

The stream endpoint is under `/api/v1/operator/events/stream`, matching the existing operator security boundary. Tenant identity is supplied by the current operator request convention until the application's authenticated tenant claim/context is introduced; the event hub still enforces tenant equality for every subscription. Heartbeats are not added in this first slice; they can be added when deployment proxy timeout values are known.

## Verification

Unit tests cover event publication, duplicate suppression, tenant filtering, subscriber cleanup, and the absence of WebFlux/Spring AI streaming dependencies. The full unit and integration test suites must pass before commit.
