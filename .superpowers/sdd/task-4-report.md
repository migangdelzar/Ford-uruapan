# Task 4 Report — Ollama separation and paused scraper

## Commands and results

- `node --test scripts/operations-contract.test.mjs scripts/workflow-contract.test.mjs` (TDD RED before implementation): **failed as expected**; 79 passed, 2 failed because the isolated Ollama role was absent and scheduler approvals were not checked.
- `node --test scripts/operations-contract.test.mjs scripts/workflow-contract.test.mjs` (final): **passed**; 81 passed, 0 failed.
- `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/site.yml`: **passed**; playbook syntax valid. Ansible warned that `data` and `app` host patterns were not present in the intentionally localhost-only inventory; no remote host was contacted.
- `git diff --check`: **passed**; no whitespace errors.
- No Docker or deployment command run. No live access used.

## Changes

- Added a separately tagged, data-host Ollama role with the approved pinned image/model, private `data_default` attachment, `ford-ollama-models` volume, health check, and no published ports or MCP/tunnel content.
- Default role start does not download a model; explicit pull requires `ollama_model_pull=true` and `ollama_model_pull_approved=true`.
- Scheduler release now fails on its first task unless both `scheduler_enabled=true` and `scheduler_ingestion_approved=true`; its Compose database target remains `/ford`.
- Added Node contract assertions for runtime isolation, model-pull opt-in, scheduler fail-closed behavior, and exclusion from normal site deployment.
- Existing MCP Compose templates left unchanged.

## Commit

`9994fc4` (`feat(deployment): isolate Ollama and pause scraper`)
