#!/usr/bin/env bash
# #2200 / #2241 — idempotent apply of the support-agent declarative config (agent + environment)
# to the Anthropic workspace the active credentials resolve to.
#
# Create-or-update BY NAME: the yaml files are the source of truth; existing resources are matched
# on their `name` and updated in place (agents use the version field as an optimistic lock), absent
# ones are created. Safe to re-run any time — a no-change apply just bumps the agent version with
# identical content. Run by BOTH:
#   - the operator, once, at bootstrap (prints the ids to wire into Render env — see README.md);
#   - CI on every main-merge touching deploy/support-agent/** (master-support-agent.yml), so the
#     deployed agent definition can never drift from the repo (docs/process/declarative-config.md).
#
# Auth: ANTHROPIC_API_KEY in the environment (CI: the SUPPORT_AGENT_ANTHROPIC_API_KEY secret), or
# an `ant auth login` profile (operator bootstrap).
set -euo pipefail

cd "$(dirname "$0")"

AGENT_NAME="wifihaven-support-responder"
ENV_NAME="wifihaven-support-env"

command -v ant >/dev/null || { echo "ERROR: the 'ant' CLI is not installed (brew install anthropics/tap/ant, or go install github.com/anthropics/anthropic-cli/cmd/ant@latest)" >&2; exit 1; }
command -v jq  >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

# ── Agent: create-or-update by name (version = optimistic lock) ────────────────
agent_id=$(ant beta:agents list --transform '{id,name}' --format jsonl \
  | jq -r --arg n "$AGENT_NAME" 'select(.name == $n) | .id' | head -n1)

if [ -z "$agent_id" ]; then
  agent_id=$(ant beta:agents create < agent.yaml --transform id -r)
  echo "created agent:      $agent_id"
else
  version=$(ant beta:agents retrieve --agent-id "$agent_id" --transform version -r)
  ant beta:agents update --agent-id "$agent_id" --version "$version" < agent.yaml > /dev/null
  echo "updated agent:      $agent_id (was version $version)"
fi

# ── Environment: create-or-update by name ─────────────────────────────────────
env_id=$(ant beta:environments list --transform '{id,name}' --format jsonl \
  | jq -r --arg n "$ENV_NAME" 'select(.name == $n) | .id' | head -n1)

if [ -z "$env_id" ]; then
  env_id=$(ant beta:environments create < environment.yaml --transform id -r)
  echo "created environment: $env_id"
else
  ant beta:environments update --environment-id "$env_id" < environment.yaml > /dev/null
  echo "updated environment: $env_id"
fi

echo
echo "Render env values (see README.md):"
echo "  WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID=$agent_id"
echo "  WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID=$env_id"
