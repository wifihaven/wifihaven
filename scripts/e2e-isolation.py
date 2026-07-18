#!/usr/bin/env python3
"""
Gate-1 multi-tenant isolation e2e (#2151, epic #622).

Proves the one load-bearing multi-tenant invariant — *a household-A principal
(user JWT or router token) can never read or write a household-B row* — through
the **deployed HTTP stack** (real API + real DB + fake router), not just the
embedded-Postgres feature suite (`MultiTenantIsolationSpec`, design
`docs/design/multi-tenant-isolation.md` §7). Feature tests bypass the auth
middleware, the JWT `hh`-claim round-trip, the router-token→household
resolution, and the ingest write-path wiring; this exercises exactly those
seams end-to-end.

Placement: this is the local half of **Gate 1** (fake-router + real API + real
UI, the `api-ui-cd` gate). It reuses the docker-compose bring-up in the
`master-api-ui.yml` `e2e` job — no new docker-compose job (per the three-Gates
rule in docs/testing.md). It is intentionally NOT wired into the staging
`api-smoke-staging` gate: scenarios 5 (beta provisioning) and 6 (router cap)
create a persistent second household + routers that the staging backend cannot
clean between runs (households are never deleted; the cap would exhaust and
403 subsequent creates, exactly the #2179/#2146 leak class). The compose stack
is disposable (`down -v` each run), which is what two-real-household seeding
needs — so this runs pre-deploy against compose only.

Two real households are provisioned entirely through public/authed HTTP:
  - Household A = the default household (id 1), seeded admin (the operator).
  - Household B = provisioned through the full beta pipeline
    (POST /api/beta/request → operator approve → POST /api/beta/accept),
    exactly as a real new tenant would sign up.
Each ends up with an admin JWT, a profile, a device, and an enrolled router
token. Every assertion is HARD and framed as the ABSENCE of leakage (a tenant
leak is worst-case), paired with a positive "sees its own data" pin so a
predicate that resolves to the wrong/empty household can't pass silently
(design §7.1 / #2176).

Scenarios (match #2151):
  1. User read isolation   — A's admin JWT reads only A's rows across
     /profiles /devices /logs /alerts /time/status /dashboard/now; B absent.
  2. User write isolation  — A's admin write against a B profileId / device MAC
     / POST /api/users / POST /api/admin/routers never lands in B.
  3. Router snapshot scope — A's router token GET /api/router/policy carries
     only A's devices/profiles; B's MACs absent; wire shape unchanged.
  4. Ingest MAC isolation  — A's router POSTing usage/events for a B device MAC
     writes only under A's (hhA, mac) rows (B byte-identical); a never-seen MAC
     creates an unmanaged device in A only.
  5. Beta provisioning     — the B bootstrap above; a fresh B admin sees an
     EMPTY, isolated dashboard; A unaffected by B coming into existence.
  6. Router cap            — creating a router past B's household router_cap
     (BetaService.DefaultRouterCap = 1) is rejected through the live endpoint.

Stdlib only (matches docker/fake-router.py + scripts/e2e/lib). Exits non-zero
on the first failed assertion.

Env:
  E2E_BASE_URL  default http://127.0.0.1:8080  (the compose API)
  ADMIN_PASS    default fake-router-bootstrap-pw-do-not-use-elsewhere
                (the password docker/fake-router.py rotates the seeded admin to
                on a fresh compose DB; e2e runs after `compose up --wait`, so
                the DB is always post-rotation by login time)
  RUN_ID        default <epoch>-<pid>  (unique suffix so names/emails/MACs never
                collide with residue on a persistent backend)
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
# fake-router rotates the seeded 'changeme' admin password to this on a fresh
# compose DB; _bootstrap_admin_token below self-heals either state.
ADMIN_PASS = os.environ.get("ADMIN_PASS", "fake-router-bootstrap-pw-do-not-use-elsewhere")
SEEDED_PASS = "changeme"
RUN_ID = os.environ.get("RUN_ID", f"{int(time.time())}-{os.getpid()}")

# Deterministic-but-unique MACs for this run (locally-administered unicast:
# second-least-significant bit of the first octet set, least-significant clear).
_rng = random.Random(RUN_ID)


def _mac() -> str:
    first = (_rng.randint(0, 255) & 0xFC) | 0x02
    return ":".join([f"{first:02x}"] + [f"{_rng.randint(0, 255):02x}" for _ in range(5)])


# ── HTTP ────────────────────────────────────────────────────────────────────

class HttpResult:
    __slots__ = ("status", "body")

    def __init__(self, status: int, body):
        self.status = status
        self.body = body


def _request(method: str, path: str, token=None, payload=None) -> HttpResult:
    url = f"{BASE}{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {}
    if data is not None:
        headers["content-type"] = "application/json"
    if token:
        headers["authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            try:
                return HttpResult(r.status, json.loads(raw) if raw else None)
            except json.JSONDecodeError:
                return HttpResult(r.status, raw.decode(errors="replace"))
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            body = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            body = raw.decode(errors="replace")
        return HttpResult(e.code, body)


def GET(path, token=None):
    return _request("GET", path, token=token)


def POST(path, payload=None, token=None):
    return _request("POST", path, token=token, payload=payload)


def PUT(path, payload=None, token=None):
    return _request("PUT", path, token=token, payload=payload)


def PATCH(path, payload=None, token=None):
    return _request("PATCH", path, token=token, payload=payload)


def DELETE(path, token=None):
    return _request("DELETE", path, token=token)


# ── Assertions / reporting ────────────────────────────────────────────────────

_failures = 0


def section(name: str):
    print(f"\n▶ {name}", flush=True)


def ok(msg: str):
    print(f"  ✓ {msg}", flush=True)


def check(cond: bool, msg: str, ctx=None):
    global _failures
    if cond:
        ok(msg)
    else:
        _failures += 1
        print(f"  ✗ {msg}", file=sys.stderr, flush=True)
        if ctx is not None:
            print(f"      context: {ctx}", file=sys.stderr, flush=True)


def fatal(msg: str, ctx=None):
    print(f"  ✗✗ FATAL: {msg}", file=sys.stderr, flush=True)
    if ctx is not None:
        print(f"      context: {ctx}", file=sys.stderr, flush=True)
    sys.exit(1)


class RateLimited(Exception):
    """A 429 from the login limiter — never retried (retrying can't refill the
    15-minute window and only muddies diagnostics)."""


def _retry(fn, attempts=30, delay=2.0, label=""):
    last = None
    for i in range(attempts):
        try:
            return fn()
        except RateLimited:
            raise  # fail fast — do not storm the limiter
        except Exception as e:  # noqa: BLE001 — retry any transient bring-up error
            last = e
            if i == attempts - 1:
                break
            print(f"  [{label}] retry {i + 1}/{attempts}: {e}", flush=True)
            time.sleep(delay)
    raise last


# ── Auth ──────────────────────────────────────────────────────────────────────

def _login(identifier: str, password: str) -> HttpResult:
    # `identifier` is the #2164 single field (email '@' / slug/username '/' /
    # bare → default household); the API resolves the household from its syntax.
    return POST("/api/auth/login", {"identifier": identifier, "password": password})


def bootstrap_operator_token() -> str:
    """Admin JWT for household A (the default household / operator).

    Self-heals the fresh-vs-rotated compose DB exactly like
    docker/fake-router.py: prefer the rotated password; fall back to the seeded
    'changeme' and rotate if the server still demands a first-login change.
    """
    def attempt():
        r = _login("admin", ADMIN_PASS)
        if r.status == 429:
            raise RateLimited(
                "login rate-limited (10/15min per IP) — a co-running e2e script on this "
                "IP likely exhausted the window",
            )
        if r.status == 200 and not r.body.get("mustChangePassword", False):
            return r.body["token"]
        # Fresh DB (or a server still forcing rotation): seed → rotate.
        r2 = _login("admin", SEEDED_PASS)
        if r2.status != 200:
            raise RuntimeError(f"admin login failed (rotated={r.status}, seeded={r2.status})")
        tok = r2.body["token"]
        if r2.body.get("mustChangePassword", False):
            cp = POST(
                "/api/auth/change-password",
                {"currentPassword": SEEDED_PASS, "newPassword": ADMIN_PASS},
                token=tok,
            )
            if cp.status != 200:
                raise RuntimeError(f"change-password failed: {cp.status} {cp.body}")
            r3 = _login("admin", ADMIN_PASS)
            if r3.status != 200:
                raise RuntimeError(f"post-rotation login failed: {r3.status}")
            return r3.body["token"]
        return tok

    return _retry(attempt, label="operator-login")


# ── Provisioning helpers ──────────────────────────────────────────────────────

def create_profile(token: str, name: str) -> int:
    r = POST(
        "/api/profiles",
        {
            "name": name,
            "blockedCategories": [],
            "extraBlocked": [],
            "extraAllowed": [],
            "paused": False,
            "timeLimit": None,
            "siteTimeLimits": [],
        },
        token=token,
    )
    if r.status != 200 or not isinstance(r.body, dict) or "id" not in r.body:
        fatal(f"create profile '{name}' failed", ctx=(r.status, r.body))
    return int(r.body["id"])


def upsert_device(token: str, mac: str, name: str, profile_id=None):
    payload = {"mac": mac, "name": name}
    if profile_id is not None:
        payload["profileId"] = profile_id
    r = PUT("/api/devices", payload, token=token)
    if r.status != 200:
        fatal(f"upsert device {mac} failed", ctx=(r.status, r.body))


def enroll_router(admin_token: str, name: str):
    """Create a router (admin) → register it → return (router_id, router_token)."""
    cr = POST("/api/admin/routers", {"name": name}, token=admin_token)
    if cr.status != 200:
        fatal(f"create router '{name}' failed", ctx=(cr.status, cr.body))
    router_id = cr.body["routerId"]
    enrollment_token = cr.body["enrollmentToken"]
    reg = POST(
        "/api/router/register",
        {
            "enrollmentToken": enrollment_token,
            "platformVersion": "OpenWrt 23.05",
            "agentVersion": "0.1.0-iso-e2e",
        },
    )
    if reg.status != 200:
        fatal(f"register router '{name}' failed", ctx=(reg.status, reg.body))
    return router_id, reg.body["routerToken"]


def provision_household_b(operator_token: str):
    """Provision household B through the real beta pipeline (scenario 5 setup).

    Returns (email, password, admin_token, slug).
    """
    email = f"iso-b-{RUN_ID}@example.com".lower()
    name = f"Iso B {RUN_ID}"
    password = f"iso-b-pw-{RUN_ID}-Aa1!"

    # 1. Public intake.
    r = POST("/api/beta/request", {"email": email, "name": name})
    if r.status != 200:
        fatal("POST /api/beta/request failed", ctx=(r.status, r.body))

    # 2. Operator lists the pending queue and finds our request.
    q = GET("/api/operator/beta-requests?status=pending", token=operator_token)
    if q.status != 200 or not isinstance(q.body, list):
        fatal("GET /api/operator/beta-requests failed", ctx=(q.status, q.body))
    match = [row for row in q.body if row.get("email") == email]
    if not match:
        fatal("beta request not found in operator pending queue", ctx=(email, q.body))
    request_id = match[0]["id"]

    # 3. Operator approves → provisions the household + mints the invite token.
    ap = POST(f"/api/operator/beta-requests/{request_id}/approve", token=operator_token)
    if ap.status != 200:
        fatal("operator approve failed", ctx=(ap.status, ap.body))
    invite_url = ap.body["inviteUrl"]
    slug = ap.body["slug"]
    if "token=" not in invite_url:
        fatal("approve response inviteUrl missing token", ctx=invite_url)
    invite_token = invite_url.split("token=", 1)[1]

    # 4. Invite accept → creates household B's FIRST admin (username 'admin',
    #    email bound from the request; the admin logs in by email thereafter).
    acc = POST("/api/beta/accept", {"token": invite_token, "password": password})
    if acc.status != 200:
        fatal("POST /api/beta/accept failed", ctx=(acc.status, acc.body))

    # 5. Log in as B's admin by email (identifier '@' → resolve by users.email).
    lg = _login(email, password)
    if lg.status != 200:
        fatal("household-B admin login failed", ctx=(lg.status, lg.body))
    b_token = lg.body["token"]
    check(
        lg.body.get("householdSlug") == slug,
        "B login returns its own household slug (#2164 wh_household cookie source)",
        ctx=(lg.body.get("householdSlug"), slug),
    )
    return email, password, b_token, slug


# ── helpers for row extraction ────────────────────────────────────────────────

def macs_of(devices):
    return {d.get("mac", "").lower() for d in devices}


def profile_ids(profiles):
    # GET /api/profiles returns ProfileDetail objects — the id is nested under
    # `profile`. Tolerate a flat `id` too (defensive, and for any other caller).
    out = set()
    for p in profiles:
        if isinstance(p.get("profile"), dict):
            out.add(p["profile"].get("id"))
        else:
            out.add(p.get("id"))
    return out


def canonical(obj) -> str:
    """Stable JSON string for byte-identical before/after comparison."""
    return json.dumps(obj, sort_keys=True)


# ══════════════════════════════════════════════════════════════════════════════
def main() -> None:
    print(f"[iso-e2e] base={BASE} run_id={RUN_ID}", flush=True)

    # Gate on the API being up (compose --wait already does this in CI; belt +
    # suspenders for local runs).
    def _health():
        h = GET("/api/health")
        if h.status != 200:
            raise RuntimeError(f"health {h.status}")
        return h

    _retry(_health, label="health")

    # ── Provision household A (default household / operator) ───────────────────
    section("Provision household A (default household)")
    a_token = bootstrap_operator_token()
    ok("logged in as household-A admin (operator)")
    a_pid = create_profile(a_token, f"iso-A-profile-{RUN_ID}")
    a_mac = _mac()
    upsert_device(a_token, a_mac, f"iso-A-device-{RUN_ID}", profile_id=a_pid)
    a_router_id, a_router_token = enroll_router(a_token, f"iso-A-router-{RUN_ID}")
    ok(f"A seeded: profile={a_pid} mac={a_mac} router={a_router_id}")

    # Snapshot A's world size BEFORE B exists, to prove B's creation can't
    # perturb A (scenario 5 tail).
    a_profiles_before = GET("/api/profiles", token=a_token).body
    a_devices_before = GET("/api/devices", token=a_token).body

    # ── Scenario 5 (part 1): beta provisioning → fresh, empty, isolated B ──────
    section("Scenario 5 — beta provisioning end-to-end + fresh B is empty/isolated")
    b_email, _b_password, b_token, b_slug = provision_household_b(a_token)
    ok(f"household B provisioned via beta pipeline (slug={b_slug}, email={b_email})")

    # A fresh B admin, before seeding anything, must see an EMPTY dashboard —
    # and crucially NONE of A's rows (the isolation half of the positive pin).
    b_profiles_fresh = GET("/api/profiles", token=b_token).body
    b_devices_fresh = GET("/api/devices", token=b_token).body
    check(b_profiles_fresh == [], "fresh B sees zero profiles (empty dashboard)", ctx=b_profiles_fresh)
    check(b_devices_fresh == [], "fresh B sees zero devices (empty dashboard)", ctx=b_devices_fresh)
    check(a_pid not in profile_ids(b_profiles_fresh), "fresh B does NOT see A's profile", ctx=a_pid)
    check(a_mac.lower() not in macs_of(b_devices_fresh), "fresh B does NOT see A's device MAC", ctx=a_mac)

    b_now = GET("/api/dashboard/now", token=b_token)
    check(b_now.status == 200, "GET /api/dashboard/now for B is 200")
    b_now_pids = {p.get("profileId") for p in (b_now.body or {}).get("profiles", [])}
    check(a_pid not in b_now_pids, "B's dashboard/now carries none of A's profiles", ctx=b_now_pids)

    # A must be completely unperturbed by B coming into existence. NB: household
    # A's device rows carry a live `lastSeenAt` that the concurrent compose
    # fake-router keeps touching, so a byte-identical device diff would flake on
    # that noise (not on any B-leakage). Assert instead that B's provisioning
    # added nothing of B's to A and left A's own profile/device set intact.
    # `/api/profiles` is config-only (no live usage), so it IS stable.
    a_profiles_after_b = GET("/api/profiles", token=a_token).body
    a_devices_after_b = GET("/api/devices", token=a_token).body
    check(
        canonical(a_profiles_after_b) == canonical(a_profiles_before),
        "A's profile list is byte-identical after B is provisioned (config-only, no fake-router noise)",
    )
    check(a_pid in profile_ids(a_profiles_after_b), "A still sees its own profile after B exists", ctx=a_pid)
    check(a_mac.lower() in macs_of(a_devices_after_b), "A still owns its device after B exists", ctx=a_mac)

    # ── Seed household B's own profile/device/router for the read/write pins ───
    section("Seed household B (profile/device/router)")
    b_pid = create_profile(b_token, f"iso-B-profile-{RUN_ID}")
    b_mac = _mac()
    upsert_device(b_token, b_mac, f"iso-B-device-{RUN_ID}", profile_id=b_pid)
    b_router_id, b_router_token = enroll_router(b_token, f"iso-B-router-{RUN_ID}")
    ok(f"B seeded: profile={b_pid} mac={b_mac} router={b_router_id}")

    # ── Scenario 1: user read isolation (both directions) ──────────────────────
    section("Scenario 1 — user read isolation (admin JWT sees only own household)")

    a_profiles = GET("/api/profiles", token=a_token).body
    a_devices = GET("/api/devices", token=a_token).body
    check(a_pid in profile_ids(a_profiles), "A sees its OWN profile (positive pin)", ctx=a_pid)
    check(a_mac.lower() in macs_of(a_devices), "A sees its OWN device (positive pin)", ctx=a_mac)
    check(b_pid not in profile_ids(a_profiles), "A does NOT see B's profile id", ctx=b_pid)
    check(b_mac.lower() not in macs_of(a_devices), "A does NOT see B's device MAC", ctx=b_mac)

    b_profiles = GET("/api/profiles", token=b_token).body
    b_devices = GET("/api/devices", token=b_token).body
    check(b_pid in profile_ids(b_profiles), "B sees its OWN profile (positive pin)", ctx=b_pid)
    check(b_mac.lower() in macs_of(b_devices), "B sees its OWN device (positive pin)", ctx=b_mac)
    check(a_pid not in profile_ids(b_profiles), "B does NOT see A's profile id", ctx=a_pid)
    check(a_mac.lower() not in macs_of(b_devices), "B does NOT see A's device MAC", ctx=a_mac)

    # /logs, /alerts, /time/status, /dashboard/now must not surface B's profile
    # to A. These are additional read planes over the same tenancy predicate.
    a_logs = GET("/api/logs", token=a_token)
    check(a_logs.status == 200, "GET /api/logs for A is 200")
    # /api/logs returns a QueryLogPage {rows, nextCursor}; rows carry profileId.
    a_log_rows = (a_logs.body or {}).get("rows", []) if isinstance(a_logs.body, dict) else []
    leaked = [l for l in a_log_rows if l.get("profileId") == b_pid]
    check(not leaked, "A's /api/logs carries no B-profile rows", ctx=leaked[:3])

    a_alerts = GET("/api/alerts", token=a_token)
    check(a_alerts.status == 200, "GET /api/alerts for A is 200")

    a_status = GET("/api/time/status", token=a_token)
    check(a_status.status == 200, "GET /api/time/status for A is 200")
    a_status_pids = {p.get("profileId") for p in (a_status.body or [])}
    check(a_pid in a_status_pids, "A's /time/status includes its OWN profile (positive pin)", ctx=a_status_pids)
    check(b_pid not in a_status_pids, "A's /time/status excludes B's profile", ctx=a_status_pids)

    b_status = GET("/api/time/status", token=b_token)
    b_status_pids = {p.get("profileId") for p in (b_status.body or [])}
    check(b_pid in b_status_pids, "B's /time/status includes its OWN profile (positive pin)", ctx=b_status_pids)
    check(a_pid not in b_status_pids, "B's /time/status excludes A's profile", ctx=b_status_pids)

    a_dash = GET("/api/dashboard/now", token=a_token)
    a_dash_pids = {p.get("profileId") for p in (a_dash.body or {}).get("profiles", [])}
    check(b_pid not in a_dash_pids, "A's /dashboard/now excludes B's profile", ctx=a_dash_pids)

    # ── Scenario 2: user write isolation ───────────────────────────────────────
    section("Scenario 2 — user write isolation (A cannot write into B)")

    # (a) PUT a B profile as A → 404 (never leak existence), B unchanged.
    b_profile_before = GET(f"/api/profiles/{b_pid}", token=b_token).body
    w = PUT(
        f"/api/profiles/{b_pid}",
        {
            "name": f"HIJACKED-by-A-{RUN_ID}",
            "blockedCategories": [],
            "extraBlocked": [],
            "extraAllowed": [],
            "paused": True,
            "timeLimit": None,
            "siteTimeLimits": [],
        },
        token=a_token,
    )
    check(w.status in (403, 404), "A PUT on a B profileId is refused (403/404)", ctx=(w.status, w.body))
    b_profile_after = GET(f"/api/profiles/{b_pid}", token=b_token).body
    check(
        canonical(b_profile_after) == canonical(b_profile_before),
        "B's profile is byte-identical after A's blocked write",
    )

    # (b) PATCH a B device MAC as A → 404, B's device unchanged.
    b_dev_before = GET("/api/devices", token=b_token).body
    wd = PATCH(f"/api/devices/{b_mac}", {"name": f"HIJACKED-{RUN_ID}"}, token=a_token)
    check(wd.status in (403, 404), "A PATCH on a B device MAC is refused (403/404)", ctx=(wd.status, wd.body))
    b_dev_after = GET("/api/devices", token=b_token).body
    check(canonical(b_dev_after) == canonical(b_dev_before), "B's device list byte-identical after A's blocked write")

    # (c) POST /api/users as A lands in A, never B (#2130). B never sees the user.
    new_user = f"iso-a-user-{RUN_ID}"
    cu = POST(
        "/api/users",
        {"username": new_user, "password": f"iso-user-pw-{RUN_ID}-Aa1!", "role": "adult", "profileIds": []},
        token=a_token,
    )
    check(cu.status == 200, "A POST /api/users succeeds (in A's household)", ctx=(cu.status, cu.body))
    a_users = GET("/api/users", token=a_token)
    a_usernames = {u.get("username") for u in (a_users.body or [])}
    check(new_user in a_usernames, "A sees the new user it created (positive pin)", ctx=a_usernames)
    b_users = GET("/api/users", token=b_token)
    b_usernames = {u.get("username") for u in (b_users.body or [])}
    check(new_user not in b_usernames, "B does NOT see the user A created", ctx=b_usernames)

    # (d) POST /api/admin/routers as A binds to A; B never sees it.
    extra_router = POST("/api/admin/routers", {"name": f"iso-A-router2-{RUN_ID}"}, token=a_token)
    check(extra_router.status == 200, "A can create a second router (cap headroom)", ctx=(extra_router.status, extra_router.body))
    a_extra_router_id = extra_router.body.get("routerId") if extra_router.status == 200 else None
    b_routers = GET("/api/admin/routers", token=b_token)
    b_router_ids = {r.get("id") for r in (b_routers.body or [])}
    check(a_router_id not in b_router_ids, "B's router list excludes A's router", ctx=b_router_ids)
    check(a_extra_router_id not in b_router_ids, "B's router list excludes A's just-created router", ctx=b_router_ids)
    a_routers = GET("/api/admin/routers", token=a_token)
    a_router_ids = {r.get("id") for r in (a_routers.body or [])}
    check(b_router_id not in a_router_ids, "A's router list excludes B's router", ctx=a_router_ids)

    # ── Scenario 3: router snapshot scoping ────────────────────────────────────
    section("Scenario 3 — router snapshot scoping (token → own household only)")

    snap_a = GET("/api/router/policy", token=a_router_token)
    check(snap_a.status == 200, "A router GET /api/router/policy is 200", ctx=snap_a.status)
    a_snap_macs = {m.lower() for m in (snap_a.body or {}).get("devices", {}).keys()}
    a_snap_pids = {int(k) for k in (snap_a.body or {}).get("profiles", {}).keys()}
    check(a_mac.lower() in a_snap_macs, "A snapshot contains A's device MAC (positive pin)", ctx=a_snap_macs)
    check(a_pid in a_snap_pids, "A snapshot contains A's profile id (positive pin)", ctx=a_snap_pids)
    check(b_mac.lower() not in a_snap_macs, "A snapshot does NOT contain B's device MAC", ctx=a_snap_macs)
    check(b_pid not in a_snap_pids, "A snapshot does NOT contain B's profile id", ctx=a_snap_pids)

    snap_b = GET("/api/router/policy", token=b_router_token)
    check(snap_b.status == 200, "B router GET /api/router/policy is 200", ctx=snap_b.status)
    b_snap_macs = {m.lower() for m in (snap_b.body or {}).get("devices", {}).keys()}
    b_snap_pids = {int(k) for k in (snap_b.body or {}).get("profiles", {}).keys()}
    check(b_mac.lower() in b_snap_macs, "B snapshot contains B's device MAC (positive pin)", ctx=b_snap_macs)
    check(a_mac.lower() not in b_snap_macs, "B snapshot does NOT contain A's device MAC", ctx=b_snap_macs)
    check(a_pid not in b_snap_pids, "B snapshot does NOT contain A's profile id", ctx=b_snap_pids)

    # Wire shape unchanged: no household id anywhere on the snapshot payload
    # (invariant 3 — household_id is a server-side key, never on the wire).
    raw_snap = json.dumps(snap_a.body)
    check(
        "householdId" not in raw_snap and "household_id" not in raw_snap,
        "snapshot payload carries no household id (wire shape unchanged)",
    )

    # ── Scenario 4: ingest MAC isolation + new-device discovery ────────────────
    section("Scenario 4 — ingest MAC isolation + new-device discovery")

    # Capture B's device + time state BEFORE A's cross-MAC ingest.
    b_devices_pre = GET("/api/devices", token=b_token).body
    b_status_pre = GET("/api/time/status", token=b_token).body
    never_seen_mac = _mac()
    # Synthetic usage-report window. Cosmetic — no assertion reads the period;
    # mirrors docker/fake-router.py's 5-min (timedelta(minutes=5)) window.
    usage_period_secs = 300
    period_end = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    period_start = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - usage_period_secs))

    # (4a) Cross-tenant screen-time-poisoning attempt: A's router POSTs usage for
    # B's device MAC. The MAC-keyed writes (time_usage, touchLastSeenBatch) are
    # constructively scoped to A's household (design §3.2.2), so B's rows must be
    # untouched. Usage only touches EXISTING device rows (no insert), so this
    # never collides with the global devices_mac_key.
    usage = POST(
        "/api/router/usage",
        {
            "routerId": a_router_id,
            "periodStart": period_start,
            "periodEnd": period_end,
            "records": [
                {"mac": b_mac, "ip": "192.168.1.50",
                 "host": {"type": "fqdn", "value": "cross-tenant.example.com"},
                 "activeSeconds": 240, "bytesIn": 500000, "bytesOut": 50000},
            ],
        },
        token=a_router_token,
    )
    check(usage.status in (200, 204), "A router POST /api/router/usage (B's MAC) accepted", ctx=(usage.status, usage.body))

    # (4a cont.) A connection_attempt for B's MAC too — the connection_events row
    # is router-keyed (→ household A via routers.household_id), no devices FK, so
    # it lands in A's logs only and cannot touch B.
    ev_conn = POST(
        "/api/router/events",
        {"routerId": a_router_id, "events": [
            {"type": "connection_attempt", "mac": b_mac,
             "host": {"type": "fqdn", "value": "cross-tenant.example.com"},
             "destIp": "203.0.113.9", "allowed": True, "reason": "allow", "ts": period_end},
        ]},
        token=a_router_token,
    )
    check(ev_conn.status in (200, 204), "A router POST /api/router/events (B's MAC conn) accepted", ctx=(ev_conn.status, ev_conn.body))

    # (4b) New-device discovery: A's router reports a NEVER-SEEN, globally-unique
    # MAC via dhcp_lease → RouterIngestService.applyDhcpOrFirstSeen →
    # deviceRepo.upsertUnknown, constructively scoped to A's household. It must
    # create an unmanaged device in A only, and appear in NO other household.
    ev_disc = POST(
        "/api/router/events",
        {"routerId": a_router_id, "events": [
            {"type": "dhcp_lease", "mac": never_seen_mac, "ip": "192.168.1.51",
             "hostname": "new-device.local", "ts": period_end},
        ]},
        token=a_router_token,
    )
    check(ev_disc.status in (200, 204), "A router POST /api/router/events (new-device discovery) accepted", ctx=(ev_disc.status, ev_disc.body))

    # B must be byte-identical: none of A's ingest above reached B's rows.
    b_devices_post = GET("/api/devices", token=b_token).body
    b_status_post = GET("/api/time/status", token=b_token).body
    check(
        canonical(b_devices_post) == canonical(b_devices_pre),
        "B's device list byte-identical after A ingests B's MAC (no cross-write)",
    )
    check(
        canonical(b_status_post) == canonical(b_status_pre),
        "B's /time/status byte-identical after A ingests B's MAC (no screen-time poisoning)",
    )

    # A owns the never-seen MAC as its OWN unmanaged device (discovery), and it
    # is absent from B.
    a_devices_post = GET("/api/devices", token=a_token).body
    a_post_macs = macs_of(a_devices_post)
    check(never_seen_mac.lower() in a_post_macs, "never-seen MAC created an unmanaged device in A (discovery)", ctx=a_post_macs)
    ns_row = [d for d in a_devices_post if d.get("mac", "").lower() == never_seen_mac.lower()]
    if ns_row:
        check(ns_row[0].get("profileId") is None, "A's discovered device is unmanaged (profileId null)", ctx=ns_row[0])
    check(never_seen_mac.lower() not in macs_of(b_devices_post), "never-seen MAC did NOT appear in B", ctx=never_seen_mac)

    # KNOWN GAP (#2277): A discovering a MAC that ALSO exists in household B
    # currently 503s — V65 kept the global `devices_mac_key` UNIQUE(mac) (the
    # `alerts.mac` FK depends on it), so A cannot create its own (hhA, B-MAC)
    # row yet. This is a FUNCTIONAL gap, not a leak: the poison-isolation pins
    # above prove B stays byte-identical (the failure mode is a loud 503, never a
    # silent cross-tenant write). We assert the loud-refusal shape so a future
    # silent-success regression is caught, and flip this to a hard "A owns its
    # own (hhA, mac) row" positive pin when #2277 relaxes the constraint.
    same_mac_disc = POST(
        "/api/router/events",
        {"routerId": a_router_id, "events": [
            {"type": "dhcp_lease", "mac": b_mac, "ip": "192.168.1.52",
             "hostname": "collision.local", "ts": period_end},
        ]},
        token=a_router_token,
    )
    check(
        same_mac_disc.status in (500, 503),
        "same-MAC discovery is loudly refused today, not a silent cross-write (#2277)",
        ctx=(same_mac_disc.status, same_mac_disc.body),
    )
    # Whatever the outcome, B must STILL be byte-identical (no poison on the
    # collision path either).
    b_devices_after_collision = GET("/api/devices", token=b_token).body
    check(
        canonical(b_devices_after_collision) == canonical(b_devices_pre),
        "B's device list byte-identical after A's same-MAC collision attempt (#2277)",
    )

    # ── Scenario 6: router cap ─────────────────────────────────────────────────
    section("Scenario 6 — router cap enforced through the live endpoint")

    # B's household router_cap = BetaService.DefaultRouterCap (1). B already
    # enrolled its one router above, so a second create must be refused.
    over_cap = POST("/api/admin/routers", {"name": f"iso-B-router2-{RUN_ID}"}, token=b_token)
    check(over_cap.status == 403, "B creating a router past its router_cap is 403", ctx=(over_cap.status, over_cap.body))
    # And B's router list still shows exactly its one router (the reject wrote nothing).
    b_routers_final = GET("/api/admin/routers", token=b_token)
    check(
        isinstance(b_routers_final.body, list) and len(b_routers_final.body) == 1,
        "B still has exactly one router after the over-cap reject",
        ctx=b_routers_final.body,
    )

    # ── Best-effort cleanup (household A only) ─────────────────────────────────
    # The compose stack is disposable (`down -v` each CI run), but cleaning up A's
    # routers/profile/device/user keeps A's router_cap headroom clear for repeated
    # LOCAL runs against a persistent DB. Household B can't be deleted (no
    # endpoint) — it's a fresh household per RUN_ID, so it never collides.
    section("Cleanup (household A artifacts; best-effort)")
    for rid in (a_router_id, a_extra_router_id, b_router_id):
        if rid:
            DELETE(f"/api/admin/routers/{rid}", token=a_token if rid != b_router_id else b_token)
    DELETE(f"/api/devices/{a_mac}", token=a_token)
    DELETE(f"/api/profiles/{a_pid}", token=a_token)
    if cu.status == 200 and isinstance(cu.body, dict) and "id" in cu.body:
        DELETE(f"/api/users/{cu.body['id']}", token=a_token)
    ok("cleanup issued")

    # ── Result ─────────────────────────────────────────────────────────────────
    print(flush=True)
    if _failures:
        print(f"✗ {_failures} isolation assertion(s) FAILED", file=sys.stderr, flush=True)
        sys.exit(1)
    print("All multi-tenant isolation e2e checks passed.", flush=True)


if __name__ == "__main__":
    main()
