#!/usr/bin/env python3
"""
fake-router — simulates an OpenWRT FamilyDNS agent in the staging stack.

Self-provisions on startup (admin login → create router → register), then
runs the same polling loop the real Lua agent runs:
  • GET /api/router/policy  (ETag-cached, every POLL_INTERVAL seconds)
  • POST /api/router/usage  (every USAGE_INTERVAL seconds)
  • POST /api/router/events (every EVENT_INTERVAL seconds)

FAKE_ROUTER_SEED controls the deterministic MACs/IPs/hostnames so e2e
assertions can make reproducible claims about what was posted.

Writes /tmp/fake-router-token once registration succeeds — the compose
healthcheck gates dependent services on this file.
"""
import json
import os
import random
import signal
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone, timedelta

API_BASE        = os.environ.get("API_BASE",        "http://localhost:8080")
ADMIN_USER      = os.environ.get("ADMIN_USER",      "admin")
ADMIN_PASS      = os.environ.get("ADMIN_PASS",      "changeme")
SEED            = int(os.environ.get("FAKE_ROUTER_SEED",  "42"))
POLL_INTERVAL   = int(os.environ.get("POLL_INTERVAL",     "3"))
USAGE_INTERVAL  = int(os.environ.get("USAGE_INTERVAL",    "10"))
EVENT_INTERVAL  = int(os.environ.get("EVENT_INTERVAL",    "15"))
TOKEN_FILE      = "/tmp/fake-router-token"

# ── Deterministic device fixtures ──────────────────────────────────────────
rng = random.Random(SEED)

def _seeded_macs(n: int) -> list[str]:
    return [":".join(f"{rng.randint(0, 255):02x}" for _ in range(6)) for _ in range(n)]

MACS      = _seeded_macs(3)
IPS       = [f"192.168.1.{10 + i}" for i in range(3)]
HOSTNAMES = ["laptop.local", "phone.local", "tablet.local"]

# ── Signal handling ────────────────────────────────────────────────────────
running = True

def _shutdown(sig, frame):
    global running
    running = False
    print("[fake-router] caught signal, stopping", flush=True)

signal.signal(signal.SIGTERM, _shutdown)
signal.signal(signal.SIGINT,  _shutdown)

# ── HTTP helpers ───────────────────────────────────────────────────────────

def _post(path: str, payload: dict, token: str | None = None) -> dict:
    data = json.dumps(payload).encode()
    headers = {"content-type": "application/json"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{API_BASE}{path}", data=data, headers=headers, method="POST"
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def _get(
    path: str, token: str | None = None, etag: str | None = None
) -> tuple[int, str | None, dict | None]:
    headers: dict[str, str] = {}
    if token:
        headers["authorization"] = f"Bearer {token}"
    if etag:
        headers["if-none-match"] = etag
    req = urllib.request.Request(f"{API_BASE}{path}", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers.get("etag"), json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code == 304:
            return 304, etag, None
        raise


def _retry(fn, attempts: int = 30, delay: float = 2.0, label: str = ""):
    for i in range(attempts):
        try:
            return fn()
        except Exception as e:
            if i == attempts - 1:
                raise
            print(f"  [{label}] retry {i + 1}/{attempts}: {e}", flush=True)
            time.sleep(delay)

# ── Provisioning ───────────────────────────────────────────────────────────

def _login() -> str:
    res = _post("/api/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASS})
    return res["token"]


def _create_router(admin_token: str) -> tuple[str, str]:
    res = _post(
        "/api/admin/routers",
        {"name": f"fake-router-seed{SEED}"},
        token=admin_token,
    )
    return res["routerId"], res["enrollmentToken"]


def _register(enrollment_token: str) -> str:
    res = _post(
        "/api/router/register",
        {
            "enrollmentToken": enrollment_token,
            "routerName":      f"fake-router-seed{SEED}",
            "platformVersion": "OpenWrt 23.05",
            "agentVersion":    "0.1.0-fake",
        },
    )
    return res["routerToken"]

# ── Reporting helpers ──────────────────────────────────────────────────────

def _now_utc() -> datetime:
    return datetime.now(timezone.utc)


def _fmt(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _post_usage(router_id: str, router_token: str) -> None:
    period_end   = _now_utc()
    period_start = period_end - timedelta(minutes=5)
    records = [
        {
            "mac":           MACS[i],
            "ip":            IPS[i],
            "hostname":      HOSTNAMES[i],
            "activeSeconds": rng.randint(30, 300),
            "bytesIn":       rng.randint(10_000, 1_000_000),
            "bytesOut":      rng.randint(1_000, 100_000),
        }
        for i in range(len(MACS))
    ]
    _post(
        "/api/router/usage",
        {
            "routerId":    router_id,
            "periodStart": _fmt(period_start),
            "periodEnd":   _fmt(period_end),
            "records":     records,
        },
        token=router_token,
    )
    print(f"[fake-router] posted usage ({len(records)} records)", flush=True)


def _post_events(router_id: str, router_token: str) -> None:
    ts = _fmt(_now_utc())
    events = [
        {
            "type":     "dhcp_lease",
            "mac":      MACS[0],
            "ip":       IPS[0],
            "hostname": HOSTNAMES[0],
            "ts":       ts,
        },
        {
            "type":     "connection_attempt",
            "mac":      MACS[1],
            "hostname": "youtube.com",
            "destIp":   "142.250.80.14",
            "allowed":  True,
            "reason":   "allow",
            "ts":       ts,
        },
    ]
    _post(
        "/api/router/events",
        {"routerId": router_id, "events": events},
        token=router_token,
    )
    print(f"[fake-router] posted events ({len(events)} events)", flush=True)

# ── Main ───────────────────────────────────────────────────────────────────

def main() -> None:
    print(f"[fake-router] seed={SEED} api={API_BASE}", flush=True)

    admin_token             = _retry(_login, label="login")
    router_id, enroll_token = _create_router(admin_token)
    print(f"[fake-router] created router {router_id}", flush=True)

    router_token = _register(enroll_token)
    print(f"[fake-router] registered, token={router_token[:8]}...", flush=True)

    with open(TOKEN_FILE, "w") as f:
        f.write(router_token)

    etag       : str | None = None
    last_usage  = 0.0
    last_event  = 0.0

    while running:
        now = time.time()

        # Policy poll
        try:
            status, new_etag, snap = _get(
                "/api/router/policy", token=router_token, etag=etag
            )
            if status != 304:
                etag = new_etag
                profiles = len(snap.get("profiles", [])) if snap else 0
                print(
                    f"[fake-router] policy etag={etag} profiles={profiles}",
                    flush=True,
                )
        except Exception as e:
            print(f"[fake-router] policy error: {e}", flush=True)

        # Usage report
        if now - last_usage >= USAGE_INTERVAL:
            try:
                _post_usage(router_id, router_token)
                last_usage = now
            except Exception as e:
                print(f"[fake-router] usage error: {e}", flush=True)

        # Events
        if now - last_event >= EVENT_INTERVAL:
            try:
                _post_events(router_id, router_token)
                last_event = now
            except Exception as e:
                print(f"[fake-router] events error: {e}", flush=True)

        time.sleep(1)

    print("[fake-router] shutdown complete", flush=True)


if __name__ == "__main__":
    main()
