# Read-Only Service Health Status Diagnostics

## Goal

Identify which Ford Service health component is DOWN when the public
`/actuator/health` endpoint returns HTTP 503, without exposing diagnostic
details or changing production state.

## Scope

- Change only `.github/workflows/deployment-preflight.yml` and its Python
  contract test.
- Parse the existing public health response and report top-level component
  names with their `UP`/`DOWN` status only.
- Omit response details, messages, exception text, credentials, and logs.
- Keep the existing root `/actuator/health` result as the sole blocking health
  gate. Component output is diagnostic only.
- Preserve read-only execution: no restart, route change, migration, or database
  write.

## Verification

- Add/adjust a contract test requiring safe component-status reporting and
  asserting sensitive detail fields are not emitted.
- Run the Python contract suite, parse workflow YAML, and run `git diff --check`.
- After checks pass and the change is on protected `main`, rerun the read-only
  deployment preflight and review only sanitized component statuses.

## Out of Scope

- Changing application security or actuator exposure.
- Printing health details, logs, environment values, or raw response bodies.
- Restarting Service or changing production routing or data.
