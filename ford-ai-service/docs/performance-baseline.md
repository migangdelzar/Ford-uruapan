# Initial MVC / virtual-thread load gate

Date: 2026-09-18. Runtime: Java 25.0.4, Spring Boot 4.1.0, Tomcat MVC,
`spring.threads.virtual.enabled=true`, local PostgreSQL/Redis containers,
single developer machine, 20 concurrent connections.

The first gate used the unauthenticated `/actuator/health` path to isolate HTTP
runtime overhead from model/provider and database business latency:

```text
ab -n 1000 -c 20 http://127.0.0.1:8080/actuator/health
```

Result: 1,000/1,000 successful requests, 739.49 requests/sec, mean 27.046 ms
per request at concurrency, p50 14 ms, p95 67 ms, p99 93 ms, maximum 97 ms.

This is a runtime gate, not a production SLO. The next load test must repeat
the same measurement for cached FAQ, live inventory, deterministic finance,
AI tool call, duplicate webhook, and authenticated operator API traffic with
realistic PostgreSQL and model-provider dependencies. WebFlux is not justified
by this result; keep MVC + virtual threads until those workload-specific SLOs
are measured.

The reproducible Node harness is `scripts/mvc-load-gate.mjs`:

```bash
FORD_LOAD_BASE_URL=http://127.0.0.1:8080 \
FORD_LOAD_PATHS=/actuator/health \
FORD_LOAD_REQUESTS=1000 FORD_LOAD_CONCURRENCY=20 \
node scripts/mvc-load-gate.mjs
```

Use a comma-separated `FORD_LOAD_PATHS` value for the authenticated/operator
and business-path gate once fixtures and credentials are provisioned. The
harness exits non-zero on HTTP or transport failures and emits p50/p95/p99,
maximum latency, throughput, and failure counts as JSON.
