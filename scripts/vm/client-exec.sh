#!/usr/bin/env bash
# Run a command on a running client VM via SSH and stream stdout/stderr.
# Exits with the remote command's exit status.
#
# Usage:
#   client-exec.sh [--name client1] -- <command...>
#
# Example:
#   scripts/vm/client-exec.sh -- dig @1.1.1.1 example.com
#
# This is the orchestrator's primary interface to the client VM.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/vm/config.sh
source "${HERE}/config.sh"

NAME="client1"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --) shift; break ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [[ $# -eq 0 ]]; then
  echo "client-exec.sh: no command given (use '-- <command...>')" >&2
  exit 2
fi

RUN_DIR="${FDNS_RUN_DIR}/${NAME}"
if [[ ! -f "${RUN_DIR}/ssh.port" ]]; then
  echo "client-exec.sh: client '${NAME}' is not running (no ${RUN_DIR}/ssh.port)" >&2
  exit 1
fi
SSH_PORT="$(cat "${RUN_DIR}/ssh.port")"

# Git doesn't track non-executable file modes; the committed private key may
# check out world-readable, which SSH refuses to use. Tighten it idempotently.
chmod 0600 "${FDNS_CLIENT_SSH_KEY}" 2>/dev/null || true

exec ssh -o StrictHostKeyChecking=no \
         -o UserKnownHostsFile=/dev/null \
         -o LogLevel=ERROR \
         -o BatchMode=yes \
         -i "${FDNS_CLIENT_SSH_KEY}" \
         -p "${SSH_PORT}" \
         root@127.0.0.1 -- "$@"
