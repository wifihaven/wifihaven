#!/bin/sh
# Tests for release-artifact signature verification in wifihaven-update
# (#2078: auto-update installed unsigned packages with --allow-untrusted).
#
# wifihaven-update must fetch the `.sig` sidecar alongside each package
# asset and verify it with `usign` against the baked-in release public key
# BEFORE calling apk add / opkg install. A missing usign binary, a failed
# sig download, or a signature mismatch must all refuse the install (fail
# closed) rather than falling through to an unverified install.
#
# Run from the openwrt/ directory: sh test/update_signature_verify_spec.sh

set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/wifihaven-update"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# ── structural checks ────────────────────────────────────────────────────────

grep -q 'usign' "$SCRIPT" \
  && check "script invokes usign for verification" ok \
  || check "script invokes usign for verification" "no usign reference"

grep -q -- '--allow-untrusted' "$SCRIPT" \
  || check "note: --allow-untrusted removed (apk-native signing not wired up here)" ok

grep -qE '\.sig' "$SCRIPT" \
  && check "script fetches a .sig sidecar" ok \
  || check "script fetches a .sig sidecar" "no .sig handling"

# ── integration tests ────────────────────────────────────────────────────────

DEFAULT_TAG="v0.2.8"
DEFAULT_ASSETS='https://example.com/wifihaven_0.2.8-1_all.apk'

setup_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-sigverify-spec.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  VERS_DIR="$TESTDIR/version"
  KEYDIR="$TESTDIR/keys"
  mkdir -p "$BINDIR" "$STATE" "$VERS_DIR" "$KEYDIR"
  : > "$TESTDIR/downloads.log"
  : > "$TESTDIR/sig_downloads.log"

  cat > "$BINDIR/logger" <<LOGEOF
#!/bin/sh
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
LOGEOF

  # Mock curl: meta fetch, asset download, and .sig sidecar download.
  cat > "$BINDIR/curl" <<CURL_EOF
#!/bin/sh
out=""
write_code=""
url=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2;;
    -w) write_code="\$2"; shift 2;;
    -*) shift;;
    *)  url="\$1"; shift;;
  esac
done
emit_meta() {
  tag="\${MOCK_TAG:-v0.2.8}"
  assets="\${MOCK_ASSETS}"
  printf '{"tag_name":"%s","assets":[' "\$tag"
  first=1
  printf '%s\n' "\$assets" | while IFS= read -r u; do
    [ -z "\$u" ] && continue
    [ \$first -eq 0 ] && printf ','
    printf '{"browser_download_url":"%s"}' "\$u"
    first=0
  done
  printf ']}'
}
if [ -n "\$write_code" ]; then
  emit_meta > "\$out"
  printf '200'
  exit 0
fi
case "\$url" in
  *.sig)
    printf '%s\n' "\$url" >> "$TESTDIR/sig_downloads.log"
    [ "\${MOCK_SIG_DOWNLOAD_FAIL:-0}" = "1" ] && exit 1
    printf 'fakesig' > "\$out"
    exit 0
    ;;
  *)
    if [ -n "\$out" ]; then
      printf '%s\n' "\$url" >> "$TESTDIR/downloads.log"
      printf 'fakepkg' > "\$out"
      exit 0
    fi
    emit_meta
    ;;
esac
CURL_EOF

  cat > "$BINDIR/jsonfilter" <<'EOF'
#!/bin/sh
expr=""
while [ $# -gt 0 ]; do
  case "$1" in
    -e) expr="$2"; shift 2;;
    *) shift;;
  esac
done
input=$(cat)
case "$expr" in
  '@.tag_name')
    printf '%s' "$input" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p'
    ;;
  '@.assets['*'].browser_download_url')
    idx=$(printf '%s' "$expr" | sed -n 's/@.assets\[\([0-9]*\)\].browser_download_url/\1/p')
    printf '%s' "$input" \
      | tr ',' '\n' \
      | sed -n 's/.*"browser_download_url":"\([^"]*\)".*/\1/p' \
      | awk -v n="$idx" 'NR==n+1 {print}'
    ;;
esac
EOF

  # Mock usign: MOCK_USIGN_EXIT controls verify result (0 = valid sig).
  cat > "$BINDIR/usign" <<USIGNEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/usign.calls"
exit \${MOCK_USIGN_EXIT:-0}
USIGNEOF

  cat > "$BINDIR/apk" <<APKEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/apk.calls"
_cmd="\$1"
case "\$_cmd" in
  info) printf 'wifihaven: agent\n'; exit 0;;
  list) exit 0;;
  add)  exit \${MOCK_APK_EXIT:-0};;
esac
exit 0
APKEOF

  INITD_WIFIHAVEN="$BINDIR/wifihaven-initd"
  cat > "$INITD_WIFIHAVEN" <<INITDEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/initd-wifihaven.calls"
exit 0
INITDEOF

  PATCHED="$TESTDIR/wifihaven-update"
  sed -e "s|STATE_DIR=/var/lib/wifihaven|STATE_DIR=$STATE|" \
      -e "s|INSTALLED_VERSION_FILE=/usr/lib/wifihaven/VERSION|INSTALLED_VERSION_FILE=$VERS_DIR/VERSION|" \
      -e "s|RELEASE_PUBKEY=/etc/wifihaven/keys/release.pub|RELEASE_PUBKEY=$KEYDIR/release.pub|" \
      -e "s|/etc/init.d/wifihaven restart|$INITD_WIFIHAVEN restart|g" \
      "$SCRIPT" > "$PATCHED"
  chmod +x "$PATCHED" "$BINDIR"/*
  printf 'untrusted comment: test key\nAAAA\n' > "$KEYDIR/release.pub"

  export ASSET_LOG="$TESTDIR/downloads.log"
  export MOCK_ASSETS="$DEFAULT_ASSETS"
  export MOCK_TAG="$DEFAULT_TAG"
}

pkg_installed()   { n=$(grep -c '^add' "$TESTDIR/apk.calls" 2>/dev/null) || n=0; printf '%s' "${n:-0}"; }
agent_stamp()     { cat "$STATE/last_update_version.wifihaven" 2>/dev/null || echo ""; }
sig_downloads()   { cat "$TESTDIR/sig_downloads.log" 2>/dev/null || echo ""; }
logger_out()      { cat "$TESTDIR/logger.out" 2>/dev/null || echo ""; }
usign_called()    { [ -f "$TESTDIR/usign.calls" ]; }

# ── Case 1: valid signature → install proceeds, stamp advances ───────────────
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

[ "$(sig_downloads)" = "https://example.com/wifihaven_0.2.8-1_all.apk.sig" ] \
  && check "[valid sig] .sig sidecar fetched" ok \
  || check "[valid sig] .sig sidecar fetched" "got: $(sig_downloads)"
usign_called \
  && check "[valid sig] usign invoked" ok \
  || check "[valid sig] usign invoked" "usign never called"
N=$(pkg_installed)
[ "$N" = "1" ] \
  && check "[valid sig] apk add called" ok \
  || check "[valid sig] apk add called" "got $N"
[ "$(agent_stamp)" = "0.2.8" ] \
  && check "[valid sig] stamp advanced" ok \
  || check "[valid sig] stamp advanced" "stamp='$(agent_stamp)'"
rm -rf "$TESTDIR"

# ── Case 2: signature verification fails → install refused, no stamp advance ─
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
export MOCK_USIGN_EXIT=1
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

N=$(pkg_installed)
[ "$N" = "0" ] \
  && check "[bad sig] apk add NOT called" ok \
  || check "[bad sig] apk add NOT called" "got $N"
[ "$(agent_stamp)" != "0.2.8" ] \
  && check "[bad sig] stamp NOT advanced" ok \
  || check "[bad sig] stamp NOT advanced" "stamp='$(agent_stamp)'"
printf '%s' "$(logger_out)" | grep -qi 'verif' \
  && check "[bad sig] failure logged" ok \
  || check "[bad sig] failure logged" "log: $(logger_out)"
unset MOCK_USIGN_EXIT
rm -rf "$TESTDIR"

# ── Case 3: .sig download fails → install refused ─────────────────────────────
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
export MOCK_SIG_DOWNLOAD_FAIL=1
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

N=$(pkg_installed)
[ "$N" = "0" ] \
  && check "[sig fetch fails] apk add NOT called" ok \
  || check "[sig fetch fails] apk add NOT called" "got $N"
[ "$(agent_stamp)" != "0.2.8" ] \
  && check "[sig fetch fails] stamp NOT advanced" ok \
  || check "[sig fetch fails] stamp NOT advanced" "stamp='$(agent_stamp)'"
unset MOCK_SIG_DOWNLOAD_FAIL
rm -rf "$TESTDIR"

# ── Case 4: usign binary missing → fail closed, no install ───────────────────
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
rm -f "$BINDIR/usign"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

N=$(pkg_installed)
[ "$N" = "0" ] \
  && check "[usign missing] apk add NOT called" ok \
  || check "[usign missing] apk add NOT called" "got $N"
printf '%s' "$(logger_out)" | grep -qi 'usign' \
  && check "[usign missing] failure logged" ok \
  || check "[usign missing] failure logged" "log: $(logger_out)"
rm -rf "$TESTDIR"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
