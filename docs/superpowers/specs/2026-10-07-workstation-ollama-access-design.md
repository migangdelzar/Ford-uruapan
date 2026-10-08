# Workstation Ollama Access Design

**Status:** Approved by user on 2026-10-07
**Scope:** Make native Ollama and the Ford MCP HTTP service on this workstation reachable to emme-1 through Cloudflare Tunnel and Access, with Ansible-managed configuration.

## Current state

- Cloudflare already has a healthy remote-managed tunnel `ford-mcp-workstation` and DNS CNAME `ford-mcp.emme.business` pointing to it.
- Its ingress routes the MCP hostname to `http://localhost:3001`; no Access applications currently protect it.
- Host Ollama listens at `127.0.0.1:11434`; the repository's Docker Ollama service on 11435 is only for embeddings.
- MCP HTTP listens at `127.0.0.1:3001/mcp` and requires its own Bearer token and validates Host/Origin.
- Current Ansible inventory targets emme VPSs. A separate localhost playbook is required so common server roles never run on macOS.

## Approved design

Reuse the existing tunnel. Preserve `ford-mcp.emme.business` → `http://localhost:3001`; add `ollama.emme.business` → `http://localhost:11434`. Add separate Cloudflare Access Service Auth applications/policies and service tokens for each hostname. The MCP request retains its current Bearer token and host validation. The emme-1 app adds Access client headers only to its Ollama requests and MCP Access headers only to its named Ford MCP connection.

Use a dedicated Ansible playbook for localhost/macOS to install/run `cloudflared` as a LaunchAgent and run the Node MCP HTTP service as a LaunchAgent, both as the current user. Keep Ollama native and bind it to loopback. Tunnel token and application credentials are supplied through the existing private deployment config/Ansible inventory (mode 0600), never templates, repo files, logs, or command output. The localhost playbook must not elevate privileges or be included in the existing VPS `site.yml` broad `all` play.

The emme-1 Ansible app environment receives Ollama and MCP hostnames, Access service credentials, and the existing MCP Bearer credential with file mode 0600. Spring AI 2.0.1 supports an OllamaApi RestClient builder; use a narrowly scoped Ollama client customization to attach Access headers without leaking credentials to other HTTP clients. Existing model ordering remains local Ollama first, then configured cloud fallbacks.

## Failure behavior and security

- Cloudflare Access denies unauthenticated requests; tunnel origins stay bound to loopback.
- Missing local tunnel or model results in existing provider failure/fallback behavior.
- MCP still requires both Cloudflare Access and the current MCP Bearer token.
- Access client ID/secret and tunnel token are distinct credentials and must not be interchanged.
- Workstation availability and outbound connectivity are runtime dependencies for the local-first model.
- No app, tunnel, or MCP endpoint is exposed without Access and MCP Bearer protections.

## Verification

- Ansible syntax check and safe localhost playbook check mode.
- Focused Spring tests for header scoping and MCP auth header composition, then build.
- MCP HTTP tests for host configuration and current bearer behavior, plus build.
- Cloudflare API readback confirms both ingress routes, Access apps/policies, and DNS routing without returning token values.
- Live smoke requests: unauthenticated calls denied; authenticated Ollama model listing and MCP initialize/tool-list succeed.

## Alternatives considered

1. **Reuse current tunnel and add Access (chosen):** least infrastructure change, preserves MCP route.
2. **Create a second tunnel:** isolates origins but duplicates connector lifecycle and DNS/Ansible state unnecessarily.
3. **Expose Ollama without Access:** rejected because Qwen's API has no built-in authentication and would be publicly callable.
