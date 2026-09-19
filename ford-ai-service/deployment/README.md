# ford-service deployment configuration

This directory contains deployment support owned by `ford-service`.

## Local observability stack

The self-hosted Langfuse and OpenTelemetry stack is defined in
`observability/langfuse/docker-compose.yml`.

From the repository root:

```bash
cd ford-ai-service/deployment/observability/langfuse
cp .env.example .env
docker compose up -d
```

Fill the required secrets in `.env` before starting the stack. The `.env` file
is ignored and must not be committed. The stack is local/self-hosted
observability support for `ford-service`; the application image and runtime
Dockerfile remain in the parent `ford-ai-service/` directory.
