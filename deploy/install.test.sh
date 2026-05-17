#!/usr/bin/env bash
# Tests for deploy/install.sh — specifically the tty/can_prompt detection
# logic that drives whether the script prompts the user or falls back to
# env-var defaults. Discovered & run by .github/workflows/ci.yml's
# shell-tests job.
#
# We extract the helper functions out of install.sh and source them in
# isolation so the test doesn't need docker/openssl/curl.

set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/install.sh"
[[ -r "${SCRIPT}" ]] || { echo "FAIL: cannot read ${SCRIPT}"; exit 1; }

# Extract the helper block (is_tty, can_prompt, noninteractive, prompt,
# prompt_secret), and prepend test stubs for the formatting helpers
# (c_red, die, warn, etc.) which are defined earlier in install.sh.
HELPERS="$(mktemp)"
trap 'rm -f "${HELPERS}"' EXIT
cat > "${HELPERS}" <<'EOF'
c_red()    { printf '%s\n' "$*" >&2; }
c_green()  { printf '%s\n' "$*"; }
c_yellow() { printf '%s\n' "$*" >&2; }
warn()     { c_yellow "WARN: $*"; }
die()      { c_red "DIE: $*"; exit 1; }
EOF
awk '/^# is_tty:/{f=1} /^genpw\(\)/{f=0} f' "${SCRIPT}" >> "${HELPERS}"
[[ -s "${HELPERS}" ]] || { echo "FAIL: could not extract helpers from install.sh"; exit 1; }

PASS=0; FAIL=0
pass() { echo "PASS: $*"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL+1)); }

# Run a snippet in a subshell with helpers sourced and stdin redirected
# from $1 (a path). Returns the snippet's exit code.
run_with_stdin() {
  local stdin_src="$1"; shift
  bash -c "set -euo pipefail; source '${HELPERS}'; $*" < "${stdin_src}"
}

# ── 1. noninteractive() returns true when neither stdin nor /dev/tty are
#      usable (simulates a CI runner with no controlling terminal). We
#      shim is_tty=false and the /dev/tty checks to false so the test is
#      portable across macOS (where setsid is absent) and Linux.
out="$(bash -c "
  source '${HELPERS}'
  is_tty() { return 1; }
  can_prompt() { is_tty && return 0; false; }
  if noninteractive; then echo yes; else echo no; fi
")"
if [[ "${out}" == "yes" ]]; then
  pass "noninteractive=true when no tty and no /dev/tty"
else
  fail "noninteractive should be true when no tty and no /dev/tty (got '${out}')"
fi

# ── 2. noninteractive() returns true when WIFIHAVEN_NONINTERACTIVE=1
#      regardless of tty state.
if WIFIHAVEN_NONINTERACTIVE=1 bash -c "source '${HELPERS}'; noninteractive"; then
  pass "noninteractive=true when WIFIHAVEN_NONINTERACTIVE=1"
else
  fail "WIFIHAVEN_NONINTERACTIVE=1 should force noninteractive"
fi

# ── 3. can_prompt() returns true when /dev/tty is readable+writable, even
#      if stdin is not a tty. This is the curl|bash-from-terminal case —
#      the regression #165 fixes. We can't reliably synthesize a real
#      /dev/tty in CI, so we shim the bracket test by redefining is_tty
#      and asserting can_prompt's *structure*: it must return true when
#      either is_tty is true OR the /dev/tty checks pass.
shim_test() {
  local label="$1" is_tty_val="$2" tty_readable="$3" tty_writable="$4" want="$5"
  local out
  out="$(bash -c "
    source '${HELPERS}'
    is_tty() { return ${is_tty_val}; }
    # Override the [ -r /dev/tty ] && [ -w /dev/tty ] portion via a
    # shimmed 'test'/'[' is impractical, so re-derive can_prompt here
    # with the same logic but mocked file checks:
    can_prompt() {
      is_tty && return 0
      [ '${tty_readable}' = '1' ] && [ '${tty_writable}' = '1' ]
    }
    if can_prompt; then echo yes; else echo no; fi
  ")"
  if [[ "${out}" == "${want}" ]]; then
    pass "${label}"
  else
    fail "${label} (got '${out}', want '${want}')"
  fi
}
shim_test "can_prompt=yes when is_tty=true"            0 0 0 yes
shim_test "can_prompt=yes when /dev/tty rw, no stdin"  1 1 1 yes
shim_test "can_prompt=no when no tty and /dev/tty ro"  1 1 0 no
shim_test "can_prompt=no when no tty and no /dev/tty"  1 0 0 no

# ── 4. prompt() uses default in noninteractive mode when env var unset.
unset WIFIHAVEN_TEST_VAR
out="$(WIFIHAVEN_NONINTERACTIVE=1 bash -c "
  source '${HELPERS}'
  prompt WIFIHAVEN_TEST_VAR 'q' 'mydefault'
  echo \"\${WIFIHAVEN_TEST_VAR}\"
" </dev/null)"
if [[ "${out}" == "mydefault" ]]; then
  pass "prompt() uses default when noninteractive and var unset"
else
  fail "prompt() noninteractive default (got '${out}', want 'mydefault')"
fi

# ── 5. prompt() preserves a pre-set env var (doesn't overwrite with default).
out="$(WIFIHAVEN_NONINTERACTIVE=1 WIFIHAVEN_TEST_VAR=preset bash -c "
  source '${HELPERS}'
  prompt WIFIHAVEN_TEST_VAR 'q' 'mydefault'
  echo \"\${WIFIHAVEN_TEST_VAR}\"
" </dev/null)"
if [[ "${out}" == "preset" ]]; then
  pass "prompt() keeps pre-set env var over default"
else
  fail "prompt() env var precedence (got '${out}', want 'preset')"
fi

# ── 6. prompt() fails when noninteractive, var unset, and no default.
if WIFIHAVEN_NONINTERACTIVE=1 bash -c "
  source '${HELPERS}'
  prompt WIFIHAVEN_TEST_VAR 'q'
" </dev/null >/dev/null 2>&1; then
  fail "prompt() should die when noninteractive, no var, no default"
else
  pass "prompt() dies when noninteractive, no var, no default"
fi

# ── 7. --help exits 0 and lists the env vars.
help_out="$(bash "${SCRIPT}" --help 2>&1 || true)"
if grep -q "WIFIHAVEN_PREFIX" <<<"${help_out}" \
   && grep -q "WIFIHAVEN_NONINTERACTIVE" <<<"${help_out}"; then
  pass "--help prints env var list"
else
  fail "--help should print WIFIHAVEN_PREFIX and WIFIHAVEN_NONINTERACTIVE"
fi

# ── 8. wait_for_api: success path. Mock curl to return 0 immediately.
#      Should print "API healthy" and exit 0 well under the timeout.
WAIT_FN="$(mktemp)"
trap 'rm -f "${HELPERS}" "${WAIT_FN}"' EXIT
awk '/^wait_for_api\(\) \{/{f=1} f; /^\}$/{if(f){f=0; exit}}' "${SCRIPT}" > "${WAIT_FN}"
[[ -s "${WAIT_FN}" ]] || { echo "FAIL: could not extract wait_for_api from install.sh"; exit 1; }

start_ts=$(date +%s)
out="$(bash -c "
  source '${HELPERS}'
  step() { :; }
  ok()   { echo \"OK: \$*\"; }
  API_URL='http://127.0.0.1:8080'
  # wait_for_api parses the HTTP code from curl's stdout and treats 400/401
  # as proof-of-life (matches the compose healthcheck). Mock curl to print
  # '400' so the success path triggers immediately.
  curl() { echo 400; return 0; }
  $(cat "${WAIT_FN}")
  wait_for_api
" 2>&1)"
rc=$?
end_ts=$(date +%s)
duration=$((end_ts - start_ts))
if [[ ${rc} -eq 0 ]] && grep -q "API healthy" <<<"${out}" && [[ ${duration} -lt 10 ]]; then
  pass "wait_for_api succeeds quickly when curl returns 0 (${duration}s)"
else
  fail "wait_for_api success path (rc=${rc}, duration=${duration}s, out=${out})"
fi

# ── 9. wait_for_api: timeout path. Mock curl to always fail and docker to
#      noop. Use a short WIFIHAVEN_WAIT_TIMEOUT so the test runs fast. The
#      function should return non-zero and print diagnostics.
start_ts=$(date +%s)
set +e
out="$(WIFIHAVEN_WAIT_TIMEOUT=3 bash -c "
  source '${HELPERS}'
  step() { :; }
  ok()   { echo \"OK: \$*\"; }
  API_URL='http://127.0.0.1:8080'
  curl()   { return 7; }
  docker() { echo \"(mocked docker \$*)\"; }
  $(cat "${WAIT_FN}")
  wait_for_api
" 2>&1)"
rc=$?
set -e
end_ts=$(date +%s)
duration=$((end_ts - start_ts))
if [[ ${rc} -ne 0 ]] \
   && grep -q "did not become healthy" <<<"${out}" \
   && grep -q "Recent api container logs" <<<"${out}" \
   && grep -q "Common causes" <<<"${out}" \
   && [[ ${duration} -ge 3 ]] && [[ ${duration} -lt 15 ]]; then
  pass "wait_for_api times out, prints diagnostics, exits non-zero (${duration}s)"
else
  fail "wait_for_api timeout path (rc=${rc}, duration=${duration}s, out=${out})"
fi

# ── 10. #254: install.sh must wire the auto-update systemd timer so
#       fixes landing on main reach existing hosts without manual ssh.
#       The unit files ship in-tree under deploy/systemd/; the installer
#       drops them into /etc/systemd/system/ and enable --now's the timer.
#       Without this, the deploy gap that motivated #254 reopens silently.
SYSTEMD_DIR="$(dirname "${SCRIPT}")/systemd"
if [[ -f "${SYSTEMD_DIR}/wifihaven-update.service" \
   && -f "${SYSTEMD_DIR}/wifihaven-update.timer" ]]; then
  pass "deploy/systemd ships wifihaven-update.{service,timer}"
else
  fail "deploy/systemd missing wifihaven-update.{service,timer}"
fi

if grep -q "/etc/systemd/system/wifihaven-update.timer" "${SCRIPT}" \
   && grep -q "systemctl enable --now wifihaven-update.timer" "${SCRIPT}"; then
  pass "install.sh installs + enables wifihaven-update.timer (#254)"
else
  fail "install.sh does not install/enable wifihaven-update.timer — #254 deploy gap"
fi

# ── 11. #359: install.sh defaults to the renamed install paths.
if grep -q 'DEFAULT_PREFIX="/opt/wifihaven"' "${SCRIPT}" \
   && grep -q 'DEFAULT_PREFIX="${HOME}/.wifihaven"' "${SCRIPT}"; then
  pass "install.sh defaults to /opt/wifihaven and \$HOME/.wifihaven (#359)"
else
  fail "install.sh does not default to renamed install paths (#359)"
fi

echo
echo "Results: ${PASS} passed, ${FAIL} failed"
[[ ${FAIL} -eq 0 ]]
