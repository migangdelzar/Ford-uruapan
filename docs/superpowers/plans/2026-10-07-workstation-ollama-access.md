# Workstation Ollama Access Implementation Plan

> **For agentic workers:** Implement task-by-task; preserve existing uncommitted changes.

**Goal:** Allow emme-1 to use workstation Ollama and Ford MCP through the existing Cloudflare tunnel with Access, configured by Ansible.

**Architecture:** Extend the healthy remote-managed workstation tunnel with an Ollama hostname and Access protection for Ollama and MCP. Use a dedicated localhost Ansible playbook for macOS agents; keep emme-1 application configuration in its existing Ansible role. Scope Cloudflare Access headers to the corresponding Spring AI clients.

**Tech Stack:** Cloudflare Tunnel/Access API, Ansible, macOS launchd, Node.js MCP HTTP transport, Spring AI 2.0.1 OllamaApi.

## Global Constraints

- Workstation services bind only to loopback.
- Access service tokens and tunnel token stay in private config/inventory, mode 0600.
- MCP keeps Bearer authentication and existing host validation.
- Do not add the workstation to `site.yml`'s broad `all` play.
- No secrets in command output, logs, source, or examples.
- Qwen local provider remains first; configured fallbacks stay as-is.

---

### Task 1: Extend Spring client auth and configuration

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/FordMcpClientAuthorizationCustomizer.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Create/modify: focused auth properties/customizer as needed
- Test: corresponding Spring configuration tests

- [ ] Write failing tests proving Access headers are scoped to Ford MCP connection and Ollama client only.
- [ ] Run focused tests to confirm failure.
- [ ] Implement property-bound credentials and narrowly scoped Spring AI 2.0.1 Ollama RestClient customization.
- [ ] Run focused tests and build.

### Task 2: Add safe workstation services playbook

**Files:**
- Create: `deployment/ansible/workstation.yml`
- Create: workstation LaunchAgent templates/tasks as needed
- Modify: `ford-ai-service/.mise/lib/remote.sh` and config examples/docs for local deploy invocation

- [ ] Add static checks for required tunnel/MCP vars and local service host/port values.
- [ ] Implement separate localhost-only Ansible setup for cloudflared and MCP service; do not mutate native Ollama service.
- [ ] Run Ansible syntax check and localhost check mode.

### Task 3: Wire emme-1 environment and secrets

**Files:**
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `ford-ai-service/.mise/lib/remote.sh`
- Modify: private config example/template and `.env` guidance

- [ ] Test inventory rendering and secret file modes without displaying values.
- [ ] Add Access client IDs/secrets for both services and hostname URLs.
- [ ] Keep app env file at mode 0600; run deployment preflight/static checks.

### Task 4: Provision Cloudflare resources and verify end-to-end

**Resources:** Existing `ford-mcp-workstation` tunnel; DNS, Access apps, policies, service tokens.

- [ ] Add `ollama.emme.business` ingress while preserving the MCP ingress and final 404 rule.
- [ ] Create Access Service Auth apps/policies and separate service tokens; retain credential values only in secure local/session state and private config.
- [ ] Read back tunnel, Access, and DNS metadata without printing credential material.
- [ ] Start/reconcile workstation LaunchAgents, deploy emme-1 via existing Ansible path, and test authorized/unauthorized Ollama and MCP calls.

## Completion Criteria

- Both protected hostnames route through the existing healthy tunnel to loopback origins.
- Access denial occurs without token headers; authorized emme-1 client calls succeed.
- MCP still rejects requests without its Bearer token.
- Ollama Qwen2.5 is first provider and cloud fallback order is unchanged.
- Ansible and focused Spring/MCP verification pass.
