# External integration setup

The repository is ready for local execution without external credentials. Copy
`.env.example` to `.env` and keep `.env` outside Git:

```bash
cd ford-ai-service
cp .env.example .env
docker compose --env-file .env up --build
```

## Values required from the environment

AI requires one configured provider:

- Local: run Ollama on the host, pull `llama3.2` and `nomic-embed-text`, and
  keep `FORD_AI_LOCAL_ENABLED=true`.
- Cloud: set `FORD_AI_CLOUD_ENABLED=true`, the cloud model name, and
  `OPENAI_API_KEY` through a secret manager. Do not put the key in Git or chat.

Meta requires:

- Meta app secret and webhook verification token;
- a public HTTPS callback URL;
- the channel provider account ID;
- an opaque webhook key and its HMAC secret for each enabled binding;
- an outbound Graph endpoint and access token for each enabled channel.

Create channel bindings in PostgreSQL after Liquibase has run. The
`webhook_key_hash` is the SHA-256 hex digest of the opaque URL key. The current
MVP stores the binding HMAC value in `secret_reference`; use a secret manager
or a dedicated secret-resolution adapter before production.

Ford ingestion requires a tenant UUID and at least one official Ford endpoint,
authorized scraper endpoint, or manual CSV path. Leave ingestion disabled until
the source contract and credentials are approved.

## Smoke checks

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/info
```

Enable Meta delivery and ingestion only after their required values are set.
The application fails configuration for a delivery channel that has an endpoint
but no access token, preventing an apparently healthy but undeliverable setup.
