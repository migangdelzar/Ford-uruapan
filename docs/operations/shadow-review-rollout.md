# Shadow review rollout

## Current status

Backend capture and operator annotation are implemented. Shadow drafts are stored
separately from customer messages, expire after seven days, and are never added to
the delivery outbox. The review API only records `ACCEPTED` or `REJECTED` plus an
optional operator note. The kill switch routes new turns to the existing human
queue. Shadow mode and the kill switch are disabled by default.

The current Ford Service repository exposes the operator inbox as an API. The
separate React inbox assumed by the original draft plan is not part of this
repository; the routes and OpenAPI contract are ready for the existing operator
client.

## Operator dry run

1. Keep customer-facing catalog tools and shadow review disabled.
2. Verify an operator token includes the correct tenant claim and
   `operator:read` / `operator:write` authority.
3. In a non-production environment, enable shadow review and send synthetic
   inbound conversations through the normal webhook and conversation workflow.
4. Confirm the customer transcript and human queue remain intact, the draft is
   visible only through the tenant-scoped review API, and no outbound message or
   channel outbox row is created for the draft.
5. Annotate a draft as accepted and another as rejected. Confirm a second review
   is refused and neither action triggers delivery.
6. Enable the kill switch and confirm the next bot turn is durably handed to the
   human queue with transcript and journey data preserved.
7. Disable both flags and confirm the normal conversation path resumes.

Do not use real customer messages for this dry run. The committed evaluation
corpus is synthetic and contains 234 cases.

## Evaluation snapshot

The deterministic test measures routing accuracy, disclosure completeness,
never-promise detection, and synthetic execution time. The latest full unit run
passed 799 tests; this is not an end-to-end provider latency benchmark. The
reviewed real-conversation set, dealership voice examples, full special-hours
calendar, and price-policy approval remain pending.

## Launch gate

After-hours shadow rollout remains blocked until the external evidence above is
provided, operators complete the dry run, and retrieval-quality and privacy
checks pass. Catalog tools use Ford Service's own Spring AI contract; Node/MCP
parity is not a launch gate. Do not enable customer-facing shadow mode or
catalog tools in production based only on synthetic tests.

## Rollback

Set `FORD_SHADOW_REVIEW_ENABLED=false` to stop draft capture. Set
`FORD_SHADOW_REVIEW_KILL_SWITCH=true` to route new turns through the existing
human queue. Keep the migration and stored drafts intact; the seven-day expiry
controls retention. Use the protected release rollback to restore the prior
application image if the deployment itself is unhealthy.
