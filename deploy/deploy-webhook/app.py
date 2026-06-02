"""Standalone Render → Grafana Cloud deploy-annotation receiver (#1245).

Render emits a webhook on each deploy lifecycle transition; this thin service
verifies the Standard-Webhooks signature, classifies the event, and POSTs a
Grafana annotation so deploys render as vertical markers across every
dashboard. It exists so "which deploy caused this?" stops being hand-archaeology
(2026-05-31 incident).

Deliberately a standalone Render service, NOT a route on the API: the API's job
is parental-control policy, and a Grafana-Cloud client there would be dead
weight for self-hosted installs. Modeled on the Alloy worker (render.yaml →
wifihaven-alloy). stdlib only — no pip deps, mirroring the opnsense/ agent.

Env (all Render-managed; see render.yaml → wifihaven-deploy-webhook):
  WEBHOOK_SECRET     Render webhook signing secret (whsec_… or plain). Required.
  GRAFANA_URL        Grafana stack base URL, e.g. https://<stack>.grafana.net
  GRAFANA_TOKEN      Grafana API token with annotations:write
  RENDER_API_TOKEN   optional — used to resolve the deployed git sha (the thin
                     webhook payload omits it); falls back to serviceName.
  ENV_OVERRIDE       optional — force the env tag instead of deriving it.
  PORT               listen port (Render injects it; default 10000).
"""

import base64
import hashlib
import hmac
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# serviceName → env tag. One receiver handles both Render API services.
_ENV_BY_SERVICE = {
    "wifihaven-api-prod": "prod",
    "wifihaven-api-staging": "staging",
}


def _log(**fields):
    """Structured stdout line — Render captures stdout; no metrics surface on a
    non-API sidecar (the 'new functionality ships with metrics' rule is
    API-process scoped)."""
    print(json.dumps(fields), flush=True)


def parse_event(body_bytes):
    """Decode the webhook JSON body. Returns the dict, or None on invalid JSON.
    Tolerates unknown fields (forward-compat with Render payload additions)."""
    try:
        ev = json.loads(body_bytes)
    except (ValueError, TypeError):
        return None
    return ev if isinstance(ev, dict) else None


def classify(ev):
    """Map a Render event to a deploy lifecycle:
    started | live | failed | canceled | ignored.

    Only 'started', 'live', and 'failed' become annotations; 'canceled' and
    'ignored' (non-deploy events) are dropped by build_annotation."""
    if not isinstance(ev, dict):
        return "ignored"
    etype = ev.get("type")
    if etype == "deploy_started":
        return "started"
    if etype == "deploy_ended":
        status = (ev.get("data") or {}).get("status")
        if status == "succeeded":
            return "live"
        if status == "failed":
            return "failed"
        if status == "canceled":
            return "canceled"
    return "ignored"


def _secret_key_bytes(secret):
    """Standard-Webhooks signing key. A 'whsec_'-prefixed secret carries a
    base64 key; a bare secret is used as raw UTF-8 bytes."""
    if secret.startswith("whsec_"):
        return base64.b64decode(secret[len("whsec_") :])
    return secret.encode()


def verify_signature(secret, webhook_id, timestamp, body_bytes, header):
    """Constant-time Standard-Webhooks verification.

    Signs '{id}.{timestamp}.{rawbody}' with HMAC-SHA256. `header` is the
    space-separated `webhook-signature` value; each entry is `v1,<base64sig>`.
    Returns True iff any entry matches. Empty secret or header never verify."""
    if not secret or not header:
        return False
    try:
        key = _secret_key_bytes(secret)
    except (ValueError, base64.binascii.Error):
        return False
    signed = webhook_id.encode() + b"." + timestamp.encode() + b"." + body_bytes
    expected = base64.b64encode(hmac.new(key, signed, hashlib.sha256).digest()).decode()
    for entry in header.split():
        version, _, sig = entry.partition(",")
        if version == "v1" and hmac.compare_digest(sig, expected):
            return True
    return False


def env_for(service_name, override=None):
    """Env tag for a Render service. Override wins; known services map to
    prod/staging; anything else is blank."""
    if override:
        return override
    return _ENV_BY_SERVICE.get(service_name or "", "")


def _timestamp_ms(ev, fallback_ms):
    """Epoch-ms for the annotation: parse the ISO-8601 payload timestamp,
    falling back to fallback_ms when it is absent or unparseable."""
    raw = ev.get("timestamp") if isinstance(ev, dict) else None
    if not raw:
        return fallback_ms
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except ValueError:
        return fallback_ms


def build_annotation(ev, lifecycle, sha, env, fallback_ms):
    """Build the Grafana annotation dict, or None for non-annotated lifecycles
    (ignored, canceled).

    text  = 'deploy <sha-or-serviceName> <lifecycle>'
    tags  = ['deploy', 'render', <env?>, <serviceName?>, <lifecycle>]
    time  = payload timestamp (ms) or fallback_ms"""
    if lifecycle in ("ignored", "canceled"):
        return None
    data = (ev or {}).get("data") or {}
    service_name = data.get("serviceName")
    ident = sha or service_name or "unknown"
    tags = ["deploy", "render"]
    if env:
        tags.append(env)
    if service_name:
        tags.append(service_name)
    tags.append(lifecycle)
    return {
        "time": _timestamp_ms(ev, fallback_ms),
        "text": f"deploy {ident} {lifecycle}",
        "tags": tags,
    }


def fetch_sha(event_id, render_token):
    """Best-effort: resolve the deployed git sha via the Render API. The thin
    webhook payload omits it. Returns a short sha or None (no token, lookup
    failure, or sha absent — caller falls back to serviceName)."""
    if not event_id or not render_token:
        return None
    req = urllib.request.Request(
        f"https://api.render.com/v1/events/{event_id}",
        headers={"Authorization": f"Bearer {render_token}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            payload = json.loads(resp.read())
    except (urllib.error.URLError, ValueError, TimeoutError) as exc:
        _log(level="warn", msg="render sha lookup failed", error=str(exc))
        return None
    # The event details nest a deploy with a commit; shapes vary, so probe
    # defensively and treat any miss as "no sha".
    details = payload.get("details") if isinstance(payload, dict) else None
    deploy = (details or {}).get("deploy") if isinstance(details, dict) else None
    commit = (deploy or {}).get("commit") if isinstance(deploy, dict) else None
    sha = (commit or {}).get("id") if isinstance(commit, dict) else None
    return sha[:7] if isinstance(sha, str) and sha else None


def post_annotation(grafana_url, grafana_token, annotation):
    """POST the annotation to Grafana. Returns True on 2xx."""
    if not grafana_url or not grafana_token:
        _log(level="warn", msg="grafana not configured; annotation dropped", annotation=annotation)
        return False
    body = json.dumps(annotation).encode()
    req = urllib.request.Request(
        grafana_url.rstrip("/") + "/api/annotations",
        data=body,
        headers={
            "Authorization": f"Bearer {grafana_token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return 200 <= resp.status < 300
    except urllib.error.HTTPError as exc:
        _log(level="error", msg="grafana annotation rejected", status=exc.code, body=exc.read().decode("utf-8", "replace"))
        return False
    except (urllib.error.URLError, TimeoutError) as exc:
        _log(level="error", msg="grafana annotation post failed", error=str(exc))
        return False


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def log_message(self, *args):  # noqa: D401 — silence default stderr access log
        pass

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, b"ok")
        else:
            self._send(404)

    def do_POST(self):
        if self.path != "/webhook":
            self._send(404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""

        secret = os.environ.get("WEBHOOK_SECRET", "")
        wid = self.headers.get("webhook-id", "")
        ts = self.headers.get("webhook-timestamp", "")
        sig = self.headers.get("webhook-signature", "")
        if not verify_signature(secret, wid, ts, body, sig):
            _log(level="warn", msg="signature verification failed", webhook_id=wid)
            self._send(401)
            return

        ev = parse_event(body)
        if ev is None:
            self._send(400)
            return

        lifecycle = classify(ev)
        if lifecycle in ("ignored", "canceled"):
            _log(level="info", msg="event skipped", lifecycle=lifecycle, type=ev.get("type"))
            self._send(204)
            return

        data = ev.get("data") or {}
        env = env_for(data.get("serviceName"), os.environ.get("ENV_OVERRIDE"))
        sha = fetch_sha(data.get("id"), os.environ.get("RENDER_API_TOKEN"))
        fallback_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        annotation = build_annotation(ev, lifecycle, sha, env, fallback_ms)

        ok = post_annotation(
            os.environ.get("GRAFANA_URL", ""),
            os.environ.get("GRAFANA_TOKEN", ""),
            annotation,
        )
        _log(
            level="info",
            msg="annotation processed",
            lifecycle=lifecycle,
            env=env,
            service=data.get("serviceName"),
            sha=sha,
            posted=ok,
        )
        self._send(202 if ok else 502)


def main():
    port = int(os.environ.get("PORT", "10000"))
    if not os.environ.get("WEBHOOK_SECRET"):
        _log(level="error", msg="WEBHOOK_SECRET unset; all webhooks will be rejected")
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    _log(level="info", msg="deploy-webhook receiver listening", port=port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        # Ctrl-C / SIGINT is a normal shutdown; the finally below closes the
        # socket cleanly, so swallow it and exit 0.
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
