# Meta webhook routing implementation plan

## Contract-first changes

1. Update unit and integration tests to post to `/webhooks/meta/{channel}` and
   configure the global Meta app secret.
2. Replace webhook-key lookup with `(channel, provider_account_id)` lookup.
3. Verify the Meta signature before parsing the provider account ID or querying
   tenant bindings.
4. Extract WhatsApp phone number ID from `metadata.phone_number_id`; retain
   Page/Instagram account extraction from `entry[0].id`.
5. Add a migration for the unique provider binding and keep legacy key columns
   nullable for existing data migration.
6. Update the binding script and integration documentation with per-tenant /
   per-number examples.

## Verification

- Unit tests for provider-account routing and signature ordering.
- Spring MVC tests for the simplified route and rejection of the old keyed
  route.
- PostgreSQL integration tests for tenant resolution and replay behavior.
- Shell syntax, Liquibase changelog parsing, and the normal Gradle test task.
