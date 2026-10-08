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

- Local: run Ollama on the host, pull `qwen2.5:3b` and `nomic-embed-text`, and
  keep `FORD_AI_LOCAL_ENABLED=true`.
- Cloud: set `FORD_AI_CLOUD_ENABLED=true`, the cloud model name, and
  `OPENAI_API_KEY` through a secret manager. Do not put the key in Git or chat.

Meta requires:

- Meta app secret and webhook verification token;
- a public HTTPS callback URL;
- one callback URL per channel (`/webhooks/meta/whatsapp`,
  `/webhooks/meta/messenger`, and `/webhooks/meta/instagram`);
- a database binding for every tenant/provider account pair;
- an outbound Graph endpoint and access token for each enabled channel. The
  current outbound MVP uses one endpoint/token per channel; use a token with
  access to all bound accounts until the per-binding credential resolver is
  introduced.

Create channel bindings in PostgreSQL after Liquibase has run. The inbound
tenant route is `(channel, provider_account_id)`, and the pair is unique. For
WhatsApp, `provider_account_id` is the phone number ID from
`entry[0].changes[0].value.metadata.phone_number_id`; it is not the WABA ID.
The Meta app secret is deployment-level configuration and is used to verify
the raw request before the binding is queried.

For a local WhatsApp binding, add `FORD_TENANT_ID`, `FORD_META_PHONE_NUMBER_ID` (or legacy `FORD_META_PHONE_ID`), and
the Meta app secret to `.env`, then run:

```bash
./bin/create-meta-binding.sh
```

The script also accepts `<META_PROVIDER_ACCOUNT_ID>` as an argument. Multiple
phone numbers and tenants use the same callback URL; create one binding per
phone number ID. Messenger uses the Page ID and Instagram uses the Instagram
account ID.

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
