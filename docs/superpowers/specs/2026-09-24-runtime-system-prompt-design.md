# Runtime System Prompt Design

## Goal

Move the inline Spring AI system prompt into a version-controlled Markdown seed while allowing an authenticated operator to replace the active prompt without restarting the service.

## Design

The default prompt lives at `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`. A JDBC-backed `ai_system_prompt` table stores the active override, version, editor, and timestamp. On each AI completion, the application reads the active database prompt and falls back to the Markdown seed when no override exists. This makes updates visible to the next request without a restart and keeps the repository version as a rollback baseline.

The operator API exposes `GET` and full-document `PUT` at `/api/v1/operator/ai/system-prompt`. The update request includes `expectedVersion`; the service rejects stale versions to prevent lost updates. Updates are restricted to `ROLE_OPERATOR`, validated for non-empty content and a bounded maximum size, and publish a `SystemPromptChanged` application event after a successful transaction.

The prompt is not streamed or interpolated with user input. Runtime customer data remains in the user message, tools, and advisors. The active prompt is applied synchronously by `SpringAiChatCompletionAdapter` on the next completion.

## Prompt content

The seed contains the supplied Spanish Ford Uruapan instructions, with the dealership address and phone number filled from the official Ford Uruapan contact listing. The requested business hours remain the values supplied by the product owner and can be changed through the prompt API if the dealership confirms different hours.

## Failure handling

- If the database has no active row, use the Markdown seed.
- Reject blank prompts and prompts over 32,000 characters.
- Reject updates whose `expectedVersion` does not match the stored version.
- Keep the old prompt active if persistence fails.
- Do not log prompt content.

## Verification

Unit tests cover seed fallback, versioned updates, stale update rejection, and controller validation. Integration tests verify the Liquibase table and the application context. Existing unit and integration suites must remain green.
