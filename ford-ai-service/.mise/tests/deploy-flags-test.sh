#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
config_file="$test_dir/oci.env"
: > "$config_file"
chmod 600 "$config_file"

MISE_PROJECT_ROOT="$project_root" FORD_DEPLOY_CONFIG_FILE="$config_file" \
  bash "$project_root/.mise/tasks/deploy/flags" FORD_CONVERSATION_GRAPH_ENABLED true FORD_HUMAN_HANDOFF_ENABLED false \
    FORD_AI_GROQ_ENABLED true FORD_AI_GEMINI_ENABLED true FORD_AI_CLOUDFLARE_ENABLED true FORD_AI_OPENROUTER_ENABLED true \
    FORD_AI_GROQ_TOOL_CALLING_VERIFIED true FORD_AI_GROQ_STRUCTURED_OUTPUT_VERIFIED false \
    FORD_AI_GEMINI_TOOL_CALLING_VERIFIED true FORD_AI_GEMINI_STRUCTURED_OUTPUT_VERIFIED false \
    FORD_AI_CLOUDFLARE_TOOL_CALLING_VERIFIED false FORD_AI_CLOUDFLARE_STRUCTURED_OUTPUT_VERIFIED false \
    FORD_AI_OPENROUTER_TOOL_CALLING_VERIFIED true FORD_AI_OPENROUTER_STRUCTURED_OUTPUT_VERIFIED true

rg -q '^FORD_CONVERSATION_GRAPH_ENABLED=true$' "$config_file"
rg -q '^FORD_HUMAN_HANDOFF_ENABLED=false$' "$config_file"
rg -q '^FORD_AI_GROQ_ENABLED=true$' "$config_file"
rg -q '^FORD_AI_GEMINI_ENABLED=true$' "$config_file"
rg -q '^FORD_AI_CLOUDFLARE_ENABLED=true$' "$config_file"
rg -q '^FORD_AI_OPENROUTER_ENABLED=true$' "$config_file"
rg -q '^FORD_AI_GROQ_TOOL_CALLING_VERIFIED=true$' "$config_file"
rg -q '^FORD_AI_GROQ_STRUCTURED_OUTPUT_VERIFIED=false$' "$config_file"
rg -q '^FORD_AI_GEMINI_TOOL_CALLING_VERIFIED=true$' "$config_file"
rg -q '^FORD_AI_GEMINI_STRUCTURED_OUTPUT_VERIFIED=false$' "$config_file"
rg -q '^FORD_AI_CLOUDFLARE_TOOL_CALLING_VERIFIED=false$' "$config_file"
rg -q '^FORD_AI_CLOUDFLARE_STRUCTURED_OUTPUT_VERIFIED=false$' "$config_file"
rg -q '^FORD_AI_OPENROUTER_TOOL_CALLING_VERIFIED=true$' "$config_file"
rg -q '^FORD_AI_OPENROUTER_STRUCTURED_OUTPUT_VERIFIED=true$' "$config_file"
[[ "$(stat -f %Lp "$config_file")" == 600 ]]

if MISE_PROJECT_ROOT="$project_root" FORD_DEPLOY_CONFIG_FILE="$config_file" \
  bash "$project_root/.mise/tasks/deploy/flags" OPENAI_API_KEY secret >/dev/null 2>&1; then
  echo "deploy:flags accepted a non-allowlisted key" >&2
  exit 1
fi

if MISE_PROJECT_ROOT="$project_root" FORD_DEPLOY_CONFIG_FILE="$config_file" \
  bash "$project_root/.mise/tasks/deploy/flags" FORD_CONVERSATION_GRAPH_ENABLED yes >/dev/null 2>&1; then
  echo "deploy:flags accepted a non-boolean value" >&2
  exit 1
fi

echo "deploy flags tests passed"
