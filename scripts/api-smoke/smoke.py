#!/usr/bin/env python3
"""Gate 1 — API contract smoke driver.

Exercises the deployed staging API as if we were a router agent + admin
operator: logs in as admin, builds up state (profile / schedule / pause /
time-limit / extraBlocked), enrolls a fake router, fetches and structurally
validates the policy snapshot, POSTs well-formed event + usage batches, and
runs a small set of negative cases per router-facing endpoint.

Designed to run from a github-hosted runner in well under 5 minutes against
https://api-staging.wifihaven.net. No qemu, no real router agent, stdlib only.

Env:
  WIFIHAVEN_API_BASE      base URL (e.g. https://api-staging.wifihaven.net)
  WIFIHAVEN_ADMIN_USER    admin username
  WIFIHAVEN_ADMIN_PASS    admin password

Flags:
  --inject-failure {auth,policy-shape,events-status,usage-status,malformed-ok}
      Force a single check to fail, used to confirm the gate catches the
      corresponding class of regression (#653 acceptance criterion).

Exits 0 on green, 1 on red. Per-check status is printed to stdout.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any


# ── HTTP plumbing ───────────────────────────────────────────────────────────


class HttpResponse:
    __slots__ = ("status", "body", "headers")

    def __init__(self, status: int, body: bytes, headers: dict[str, str]):
        self.status = status
        self.body = body
        self.headers = headers

    def json(self) -> Any:
        if not self.body:
            return None
        return json.loads(self.body.decode("utf-8"))

    def text(self) -> str:
        return self.body.decode("utf-8", errors="replace")


def http(
    method: str,
    url: str,
    *,
    body: Any = None,
    raw_body: bytes | None = None,
    headers: dict[str, str] | None = None,
    bearer: str | None = None,
    timeout: float = 20.0,
) -> HttpResponse:
    h = {"accept": "application/json"}
    if headers:
        h.update(headers)
    if bearer:
        h["authorization"] = f"Bearer {bearer}"
    data: bytes | None
    if raw_body is not None:
        data = raw_body
        h.setdefault("content-type", "application/json")
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
        h["content-type"] = "application/json"
    else:
        data = None
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
            return HttpResponse(
                resp.status,
                resp.read(),
                {k.lower(): v for k, v in resp.headers.items()},
            )
    except urllib.error.HTTPError as e:
        return HttpResponse(
            e.code,
            e.read() if e.fp is not None else b"",
            {k.lower(): v for k, v in (e.headers or {}).items()},
        )


# ── result tracking ─────────────────────────────────────────────────────────


@dataclass
class Result:
    failures: list[str] = field(default_factory=list)
    passed: int = 0

    def ok(self, name: str) -> None:
        self.passed += 1
        print(f"  PASS  {name}")

    def fail(self, name: str, detail: str) -> None:
        self.failures.append(f"{name}: {detail}")
        print(f"  FAIL  {name} -- {detail}")

    def check(self, name: str, cond: bool, detail: str = "") -> bool:
        if cond:
            self.ok(name)
            return True
        self.fail(name, detail or "condition false")
        return False


# ── driver ──────────────────────────────────────────────────────────────────


def run(base: str, user: str, pw: str, inject: str | None) -> int:
    r = Result()
    run_tag = f"smoke-{int(time.time())}-{uuid.uuid4().hex[:6]}"
    print(f"== Gate 1 smoke against {base}  tag={run_tag}  inject={inject or 'none'} ==\n")

    # Track created state so we can clean up even on partial success.
    created_profile_id: int | None = None
    created_router_id: str | None = None
    router_token: str | None = None
    admin_token: str | None = None

    try:
        # ── 1. admin auth ───────────────────────────────────────────────────
        print("[1] admin auth")
        login_pw = pw + "_wrong" if inject == "auth" else pw
        resp = http("POST", f"{base}/api/auth/login",
                    body={"username": user, "password": login_pw})
        if not r.check("login 200", resp.status == 200,
                       f"got {resp.status}: {resp.text()[:200]}"):
            return _finish(r)
        admin_token = resp.json().get("token")
        r.check("login returns token", bool(admin_token), "token missing in body")

        bad = http("POST", f"{base}/api/auth/login",
                   body={"username": user, "password": "definitely-not-the-password"})
        r.check("login wrong password rejected", bad.status in (401, 403),
                f"expected 401/403 got {bad.status}")

        # ── 2. admin setup: profile + schedule + pause + time-limit + extraBlocked ─
        print("\n[2] admin state setup")
        profile_body = {
            "name": f"{run_tag}-profile",
            "blockedCategories": [],
            "extraBlocked": [f"{run_tag}.example.com", "blocked.example.org"],
            "extraAllowed": ["allowed.example.org"],
            "paused": False,
            "schedules": [],
            "timeLimit": None,
            "siteTimeLimits": [],
            "failureMode": "block-all",
        }
        resp = http("POST", f"{base}/api/profiles", body=profile_body, bearer=admin_token)
        if not r.check("create profile 200", resp.status == 200,
                       f"got {resp.status}: {resp.text()[:200]}"):
            return _finish(r)
        pj = resp.json() or {}
        created_profile_id = (pj.get("profile") or pj).get("id")
        r.check("profile has id", isinstance(created_profile_id, int),
                f"id missing in {pj!r}")

        # Add a schedule via PUT-with-merge.
        full = http("GET", f"{base}/api/profiles/{created_profile_id}",
                    bearer=admin_token).json() or {}
        prof = full.get("profile") or full
        merged = {
            "name": prof.get("name"),
            "blockedCategories": prof.get("blockedCategories", []),
            "extraBlocked": prof.get("extraBlocked", []),
            "extraAllowed": prof.get("extraAllowed", []),
            "paused": True,                                # pause
            "schedules": (full.get("schedules") or []) + [
                {
                    "daysOfWeek": ["MON", "TUE"],
                    "startTime": "09:00",
                    "endTime": "17:00",
                    "timezone": "America/Los_Angeles",
                }
            ],
            "timeLimit": 60,                               # 60 min/day
            "siteTimeLimits": full.get("siteTimeLimits", []),
            "failureMode": prof.get("failureMode", "block-all"),
        }
        upd = http("PUT", f"{base}/api/profiles/{created_profile_id}",
                   body=merged, bearer=admin_token)
        r.check("update profile (pause+schedule+timeLimit) 200",
                upd.status == 200, f"got {upd.status}: {upd.text()[:200]}")

        # ── 3. enroll fake router ───────────────────────────────────────────
        print("\n[3] fake router enrollment")
        resp = http("POST", f"{base}/api/admin/routers",
                    body={"name": f"{run_tag}-router"}, bearer=admin_token)
        if not r.check("create router 200", resp.status == 200,
                       f"got {resp.status}: {resp.text()[:200]}"):
            return _finish(r)
        cj = resp.json() or {}
        created_router_id = cj.get("routerId")
        enrollment_token = cj.get("enrollmentToken")
        r.check("create router returns enrollmentToken",
                bool(created_router_id) and bool(enrollment_token),
                f"missing fields in {cj!r}")

        reg = http("POST", f"{base}/api/router/register",
                   body={"enrollmentToken": enrollment_token})
        if not r.check("register router 200", reg.status == 200,
                       f"got {reg.status}: {reg.text()[:200]}"):
            return _finish(r)
        rj = reg.json() or {}
        router_token = rj.get("routerToken")
        r.check("register returns routerToken", bool(router_token),
                f"missing token in {rj!r}")
        r.check("register returns matching routerId",
                rj.get("routerId") == created_router_id,
                f"got {rj.get('routerId')!r} want {created_router_id!r}")

        bad_reg = http("POST", f"{base}/api/router/register",
                       body={"enrollmentToken": "et_bogus_does_not_exist"})
        r.check("register with bogus token rejected",
                bad_reg.status in (401, 400),
                f"expected 401/400 got {bad_reg.status}")

        # ── 4. policy snapshot ──────────────────────────────────────────────
        print("\n[4] policy snapshot shape + content")
        # Two-phase: agent's first poll often returns a stale etag, so retry briefly
        # to give the snapshot writer a chance to include our new profile.
        snap = _fetch_policy_with_retry(base, router_token, run_tag,
                                        created_profile_id, r)
        if snap is None:
            return _finish(r)

        # Structural assertions against shared/contract/api-to-router/policy_snapshot.json.
        if inject == "policy-shape":
            snap = dict(snap)
            snap.pop("profiles", None)  # break shape to confirm gate trips
        _assert_policy_shape(snap, r)

        # Content assertions: our configured profile appears with the rules we set.
        profiles = snap.get("profiles") or {}
        pkey = str(created_profile_id)
        prof_in_snap = profiles.get(pkey)
        if r.check("created profile present in snapshot", prof_in_snap is not None,
                   f"id {pkey!r} not in {list(profiles.keys())}"):
            rules = (prof_in_snap or {}).get("rules") or {}
            extra_blocked = rules.get("extraBlocked") or []
            r.check("profile rules carry our extraBlocked entry",
                    f"{run_tag}.example.com" in extra_blocked,
                    f"got {extra_blocked!r}")
            r.check("profile rules.blocked is bool",
                    isinstance(rules.get("blocked"), bool),
                    f"got {rules.get('blocked')!r}")

        # ETag round-trip: If-None-Match should yield 304.
        etag = snap.get("etag")
        if etag:
            req = urllib.request.Request(
                f"{base}/api/router/policy",
                headers={
                    "authorization": f"Bearer {router_token}",
                    "If-None-Match": etag,
                    "accept": "application/json",
                },
                method="GET",
            )
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310
                    r.check("policy 304 on If-None-Match match",
                            resp.status == 304, f"got {resp.status}")
            except urllib.error.HTTPError as e:
                r.check("policy 304 on If-None-Match match",
                        e.code == 304, f"got {e.code}")

        # ── 5. events ingest ────────────────────────────────────────────────
        print("\n[5] events ingest")
        events_status_floor = 500 if inject == "events-status" else 0
        events_body = {
            "routerId": created_router_id,
            "events": [
                {
                    "type": "connection_attempt",
                    "mac": "02:e2:e2:00:00:01",
                    "host": {"type": "fqdn", "value": "example.org"},
                    "destIp": "93.184.216.34",
                    "allowed": True,
                    "reason": "allow",
                    "ts": _now_iso(),
                    "eventId": str(uuid.uuid4()),
                },
                {
                    "type": "dhcp_lease",
                    "mac": "02:e2:e2:00:00:01",
                    "ip": "192.0.2.10",
                    "hostname": "smoke-client",
                    "ts": _now_iso(),
                },
            ],
        }
        ev = http("POST", f"{base}/api/router/events",
                  body=events_body, bearer=router_token)
        r.check("events well-formed 2xx",
                200 <= ev.status < 300 and ev.status >= events_status_floor,
                f"got {ev.status}: {ev.text()[:300]}")

        # Negative: missing auth -> 401.
        ev_noauth = http("POST", f"{base}/api/router/events", body=events_body)
        r.check("events missing auth -> 401",
                ev_noauth.status == 401,
                f"got {ev_noauth.status}")

        # Negative: malformed payload -> 4xx.
        malformed_raw = b'{"this is": "not router events JSON"'
        ev_bad = http("POST", f"{base}/api/router/events",
                      raw_body=malformed_raw, bearer=router_token)
        if inject == "malformed-ok":
            r.check("events malformed -> 4xx", 200 <= ev_bad.status < 300,
                    f"injected: pretending 4xx is wrong, got {ev_bad.status}")
        else:
            r.check("events malformed -> 4xx",
                    400 <= ev_bad.status < 500,
                    f"got {ev_bad.status}: {ev_bad.text()[:200]}")

        # ── 6. usage ingest ─────────────────────────────────────────────────
        print("\n[6] usage ingest")
        usage_status_floor = 500 if inject == "usage-status" else 0
        period_end = _now_iso()
        period_start = _now_iso(offset_s=-300)
        usage_body = {
            "routerId": created_router_id,
            "periodStart": period_start,
            "periodEnd": period_end,
            "records": [
                {
                    "mac": "02:e2:e2:00:00:01",
                    "ip": "192.0.2.10",
                    "host": {"type": "fqdn", "value": "example.org"},
                    "activeSeconds": 30,
                    "bytesIn": 1024,
                    "bytesOut": 512,
                },
            ],
        }
        us = http("POST", f"{base}/api/router/usage",
                  body=usage_body, bearer=router_token)
        r.check("usage well-formed 2xx",
                200 <= us.status < 300 and us.status >= usage_status_floor,
                f"got {us.status}: {us.text()[:300]}")

        us_noauth = http("POST", f"{base}/api/router/usage", body=usage_body)
        r.check("usage missing auth -> 401",
                us_noauth.status == 401, f"got {us_noauth.status}")

        # ── 7. policy missing auth -> 401 ───────────────────────────────────
        print("\n[7] policy negative auth")
        bad_policy = http("GET", f"{base}/api/router/policy")
        r.check("policy missing auth -> 401",
                bad_policy.status == 401, f"got {bad_policy.status}")
        bad_policy2 = http("GET", f"{base}/api/router/policy",
                           bearer="rt_bogus_does_not_exist")
        r.check("policy bogus bearer -> 401",
                bad_policy2.status == 401, f"got {bad_policy2.status}")

    finally:
        # ── teardown (best effort) ──────────────────────────────────────────
        print("\n[teardown]")
        if admin_token and created_router_id:
            try:
                http("DELETE", f"{base}/api/admin/routers/{created_router_id}",
                     bearer=admin_token)
                print(f"  deleted router {created_router_id}")
            except Exception as e:
                print(f"  router teardown failed (ok to ignore): {e}")
        if admin_token and created_profile_id:
            try:
                http("DELETE", f"{base}/api/profiles/{created_profile_id}",
                     bearer=admin_token)
                print(f"  deleted profile {created_profile_id}")
            except Exception as e:
                print(f"  profile teardown failed (ok to ignore): {e}")

    return _finish(r)


def _fetch_policy_with_retry(
    base: str, router_token: str, run_tag: str, profile_id: int, r: Result,
) -> dict[str, Any] | None:
    """Poll /api/router/policy until our new profile appears, or 30s elapses.

    The snapshot is rebuilt on writes; in steady-state staging the read is
    immediate, but we give it a few retries to avoid a flake on a slow tick.
    """
    deadline = time.time() + 30
    last: HttpResponse | None = None
    while time.time() < deadline:
        last = http("GET", f"{base}/api/router/policy", bearer=router_token)
        if last.status == 200:
            try:
                snap = last.json()
            except Exception:  # noqa: BLE001
                snap = None
            if isinstance(snap, dict) and str(profile_id) in (snap.get("profiles") or {}):
                r.ok("policy snapshot 200 (with our profile)")
                return snap
        time.sleep(2)
    if last is None:
        r.fail("policy snapshot 200", "no response")
        return None
    if last.status != 200:
        r.fail("policy snapshot 200", f"got {last.status}: {last.text()[:300]}")
        return None
    # 200 but our profile never appeared — still return the snapshot for shape
    # assertions, but mark the content check as a soft fail.
    r.fail("policy snapshot contains our profile within 30s",
           "snapshot fetched but our profile id absent")
    try:
        return last.json()
    except Exception:  # noqa: BLE001
        return None


def _assert_policy_shape(snap: Any, r: Result) -> None:
    """Structural check against shared/contract/api-to-router/policy_snapshot.json.

    Key presence + type only. IDs and timestamps differ between staging and
    the golden, so no byte-compare. The contract golden has drifted on master
    (#653 PR notes); this routine intentionally checks what the deployed API
    actually emits today.
    """
    if not r.check("snapshot is object", isinstance(snap, dict),
                   f"got {type(snap).__name__}"):
        return
    for key, expected_type, label in [
        ("etag", str, "str"),
        ("generatedAt", str, "str"),
        ("devices", dict, "object"),
        ("profiles", dict, "object"),
        ("blocklists", dict, "object"),
    ]:
        r.check(f"snapshot.{key} present and {label}",
                key in snap and isinstance(snap[key], expected_type),
                f"got {type(snap.get(key)).__name__}={snap.get(key)!r:.80s}")
    # Validate the shape of one arbitrary profile entry, if any exist.
    profiles = snap.get("profiles") or {}
    if profiles:
        any_pid = next(iter(profiles))
        prof = profiles[any_pid]
        r.check("profile entry has name+rules",
                isinstance(prof, dict)
                and isinstance(prof.get("name"), str)
                and isinstance(prof.get("rules"), dict),
                f"got {prof!r:.120s}")
        rules = prof.get("rules") or {}
        for key, label in [
            ("blocked", "bool"),
            ("extraBlocked", "list"),
            ("extraAllowed", "list"),
            ("blocklistIds", "list"),
            ("blockIpOnly", "bool"),
        ]:
            r.check(f"profile.rules.{key} present",
                    key in rules, f"missing in {list(rules.keys())}")


def _now_iso(offset_s: int = 0) -> str:
    import datetime as dt
    t = dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=offset_s)
    # Drop microseconds + use Z suffix to match Instant.parse on the server.
    return t.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _finish(r: Result) -> int:
    print(f"\n== summary: {r.passed} passed, {len(r.failures)} failed ==")
    if r.failures:
        for f in r.failures:
            print(f"  - {f}")
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--inject-failure",
        choices=["auth", "policy-shape", "events-status", "usage-status", "malformed-ok"],
        default=None,
        help="Force a check to fail. Used to validate the gate catches regressions.",
    )
    args = parser.parse_args()

    base = os.environ.get("WIFIHAVEN_API_BASE", "").rstrip("/")
    user = os.environ.get("WIFIHAVEN_ADMIN_USER", "")
    pw = os.environ.get("WIFIHAVEN_ADMIN_PASS", "")
    missing = [n for n, v in [("WIFIHAVEN_API_BASE", base),
                              ("WIFIHAVEN_ADMIN_USER", user),
                              ("WIFIHAVEN_ADMIN_PASS", pw)] if not v]
    if missing:
        print(f"missing required env vars: {', '.join(missing)}", file=sys.stderr)
        return 2

    return run(base, user, pw, args.inject_failure)


if __name__ == "__main__":
    sys.exit(main())
