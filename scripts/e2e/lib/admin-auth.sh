# Self-healing admin auth helper for staging smoke jobs. See #1790.
#
# Why this exists: staging's Postgres resets every 30 days (free-tier
# behavior). On reset the seeded admin password reverts to 'changeme' with
# the must-change flag set. CI's stored secret holds the operator-chosen
# value — so without this helper, the next CD's auth step 401s and every
# staging-gated job (Gate 1, Gate 3a, Gate 3b) cascade-fails until someone
# rotates the secret by hand.
#
# This file is `source`d, not executed. Callers invoke `admin_login_self_heal`
# and capture the JWT from stdout. Diagnostic output goes to stderr; the
# token itself is never logged.
#
# Algorithm (the one source of truth — Python's AdminAPI.login mirrors it):
#   1. POST /api/auth/login {STAGING_ADMIN_USER, STAGING_ADMIN_PASSWORD}
#   2. If 200 → echo token, return 0.
#   3. If 401 → POST /api/auth/login {admin, 'changeme'} (the post-reset
#      bootstrap password).
#      a. If 200 → POST /api/auth/change-password to rotate back to
#         STAGING_ADMIN_PASSWORD, then re-login with that, then echo token.
#         (Per #623 /api/auth/change-password returns JSON, not empty.)
#      b. If 401 → hard fail with a clear message naming both arms.
#   4. Any other HTTP status → propagate the error; do NOT swallow.
#
# Env required (callers set these):
#   STAGING_API_URL          base URL (e.g. https://api-staging.wifihaven.net)
#   STAGING_ADMIN_USER       admin username (defaults to 'admin')
#   STAGING_ADMIN_PASSWORD   operator-chosen password (CI secret)

_admin_auth_log() { echo "[admin-auth] $*" >&2; }

# admin_login_self_heal — echoes a JWT on stdout, exits 0 on success.
# On hard failure prints a clear stderr message and returns non-zero.
admin_login_self_heal() {
  local base="${STAGING_API_URL:?STAGING_API_URL not set}"
  local user="${STAGING_ADMIN_USER:-admin}"
  local stored="${STAGING_ADMIN_PASSWORD:?STAGING_ADMIN_PASSWORD not set}"
  local default_pw="changeme"

  base="${base%/}"
  local tmp
  tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN

  local body_file="$tmp/body.json"
  local code

  _admin_auth_log "attempting login with stored password"
  code=$(_admin_auth_post_login "$base" "$user" "$stored" "$body_file")
  case "$code" in
    200)
      _admin_auth_emit_token "$body_file" || return 1
      return 0
      ;;
    401)
      : # fall through to the self-heal path
      ;;
    *)
      _admin_auth_log "unexpected HTTP $code from /api/auth/login (stored password)"
      cat "$body_file" >&2 2>/dev/null || true
      return 1
      ;;
  esac

  _admin_auth_log "stored password rejected; trying post-reset bootstrap ('changeme')"
  code=$(_admin_auth_post_login "$base" "$user" "$default_pw" "$body_file")
  case "$code" in
    200)
      : # got changeme working; continue to rotation
      ;;
    401)
      echo "admin-auth: neither stored password nor 'changeme' worked on $base — staging admin credentials need manual rotation" >&2
      return 1
      ;;
    *)
      _admin_auth_log "unexpected HTTP $code on changeme login"
      cat "$body_file" >&2 2>/dev/null || true
      return 1
      ;;
  esac

  local bootstrap_token
  bootstrap_token=$(_admin_auth_extract_token "$body_file") || {
    _admin_auth_log "changeme login returned no token"
    return 1
  }

  _admin_auth_log "rotating admin password back to stored value"
  local cp_body="$tmp/cp.json"
  code=$(curl -sS -o "$cp_body" -w '%{http_code}' \
    -X POST "$base/api/auth/change-password" \
    -H 'content-type: application/json' \
    -H "authorization: Bearer $bootstrap_token" \
    -d "$(_admin_auth_json_cp "$default_pw" "$stored")" || echo "000")
  if [ "$code" != "200" ]; then
    _admin_auth_log "change-password failed: HTTP $code"
    cat "$cp_body" >&2 2>/dev/null || true
    return 1
  fi
  # #623 — body is JSON {"mustChangePassword": false}. We don't strictly
  # need to read it, but verify the response is parseable JSON so a future
  # regression to empty body is caught loudly.
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$cp_body" >/dev/null 2>&1; then
    _admin_auth_log "change-password returned non-JSON body (regression of #623?)"
    return 1
  fi

  _admin_auth_log "re-logging in with the now-restored stored password"
  code=$(_admin_auth_post_login "$base" "$user" "$stored" "$body_file")
  if [ "$code" != "200" ]; then
    _admin_auth_log "post-rotation login failed: HTTP $code"
    cat "$body_file" >&2 2>/dev/null || true
    return 1
  fi
  _admin_auth_emit_token "$body_file" || return 1
  return 0
}

# Internals. Underscored to discourage callers from depending on them.

_admin_auth_json_login() {
  python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' "$1" "$2"
}

_admin_auth_json_cp() {
  python3 -c 'import json,sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' "$1" "$2"
}

_admin_auth_post_login() {
  local base="$1" user="$2" pw="$3" body_file="$4"
  curl -sS -o "$body_file" -w '%{http_code}' \
    -X POST "$base/api/auth/login" \
    -H 'content-type: application/json' \
    -d "$(_admin_auth_json_login "$user" "$pw")" || echo "000"
}

_admin_auth_extract_token() {
  python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); t=b.get("token") or ""; print(t); sys.exit(0 if t else 1)' "$1"
}

_admin_auth_emit_token() {
  local body_file="$1" token
  token=$(_admin_auth_extract_token "$body_file") || {
    _admin_auth_log "login response missing token"
    return 1
  }
  printf '%s\n' "$token"
}
