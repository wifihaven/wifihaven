"""Gate 3 fixtures — drives the real staging API via the public admin
surface only (no /api/debug/* — staging doesn't expose it).

Reuses lib/vm + lib/enrollment primitives + lib/wait verbatim. Skips
lib/stack and lib/api_debug (Gate 3 talks to a remote API, not a local
docker stack, and asserts behavior via the public surface).

Namespacing — every artifact written to the shared staging instance is
suffixed with WH_RUN_ID-PKG_FORMAT-SIDE so concurrent PRs on the same
staging do not collide. Teardown is best-effort in finally blocks; if a
run aborts hard, a periodic cleanup job (filed separately) would scrub
orphans.
"""
from __future__ import annotations

import hashlib
import logging
import os

import pytest

from lib.api_admin import AdminAPI
from lib.app_seed import pick_block_allow_apps
from lib.enrollment import exchange_enrollment_token, provision_router_uci
from lib.vm import Client, client_down, client_up, router_down, router_up
from lib.wait import (
    policy_change_baseline,
    wait_for_client_dns,
    wait_for_etag_change,
    wait_for_router_active,
)

log = logging.getLogger(__name__)


WH_API_URL = os.environ.get("WH_API_URL")  # e.g. https://api-staging.wifihaven.net
# #2535/#2567: this identity must be the OPERATOR — the admin of household 1
# (`HouseholdId.Default`). The default `admin` is, by V1's seed + V65's backfill. The suite calls
# `POST /api/apps/seed-from-templates`, which mutates the install-wide `apps` catalog and is now
# `requireOperator`; pointing this at a per-run / beta household's admin would 403 the fixture.
WH_ADMIN_USER = os.environ.get("WH_ADMIN_USER", "admin")
WH_ADMIN_PASS = os.environ.get("WH_ADMIN_PASS")
WH_RUN_ID = os.environ.get("WH_RUN_ID", "local")
WH_GATE3_SIDE = os.environ.get("WH_GATE3_SIDE", "3a")  # 3a (router-side) or 3b (api-side)
PKG_FORMAT = os.environ.get("PKG_FORMAT", "ipk")
ROUTER_IMAGE_PATH = os.environ.get("WH_ROUTER_IMAGE_PATH")
KEEP_VMS = os.environ.get("E2E_VM_KEEP", "0") == "1"


def _suffix() -> str:
    # Short, filesystem/DB-safe. 'gate3-<run>-<arm>-<side>' keeps profile
    # names readable at a glance and stays well under the API's name length
    # cap (typically 64 chars).
    return f"{WH_RUN_ID}-{PKG_FORMAT}-{WH_GATE3_SIDE}"


def _suffix_mac() -> str:
    """Deterministic locally-administered MAC keyed on the run suffix.

    Two PRs racing on staging get distinct MACs; a retry of the same run
    re-uses the same MAC (which is fine because teardown runs in finally
    and we PUT, not POST, the device record).
    """
    h = hashlib.sha256(_suffix().encode()).hexdigest()
    # 02:e2:e3:xx:xx:xx — bit 0x02 set marks it locally-administered, unicast.
    # Distinct from the 02:e2:e2:* range used by the live-mode harness.
    return "02:e2:e3:" + ":".join(h[i:i+2] for i in (0, 2, 4))


# ── session-scoped infrastructure ────────────────────────────────────────


@pytest.fixture(scope="session")
def admin() -> AdminAPI:
    if not WH_API_URL:
        pytest.fail("WH_API_URL not set (e.g. https://api-staging.wifihaven.net)")
    if not WH_ADMIN_PASS:
        pytest.fail("WH_ADMIN_PASS not set")
    a = AdminAPI(WH_API_URL, username=WH_ADMIN_USER, password=WH_ADMIN_PASS)
    a.login()
    return a


@pytest.fixture(scope="session")
def enrolled_router(admin):
    """Boot the router VM, enroll it against staging, leave it running.

    Single boot, no snapshot/restore loop — Gate 3 has one scenario.
    """
    if not ROUTER_IMAGE_PATH:
        pytest.fail("WH_ROUTER_IMAGE_PATH not set")

    name = f"gate3-{_suffix()}"
    router_up(image_path=ROUTER_IMAGE_PATH)

    created = admin.create_router(name=name)
    router_id = created["routerId"]
    enrollment_token = created["enrollmentToken"]

    register_url = f"{WH_API_URL.rstrip('/')}/api/router/register"
    rid, rtoken = exchange_enrollment_token(
        register_url=register_url, enrollment_token=enrollment_token,
    )
    assert rid == router_id, f"register returned {rid!r}, admin issued {router_id!r}"

    # Point the agent at staging directly. The router's WAN reaches the
    # internet through SLIRP (see wait_for_router_slirp_ready's probe (b)),
    # so HTTPS to api-staging.wifihaven.net works without any host-side
    # proxying.
    provision_router_uci(router_id=router_id, router_token=rtoken, api_url=WH_API_URL)

    # Confirm the agent's first poll landed.
    wait_for_router_active(admin, router_id, timeout_s=180)

    yield {"router_id": router_id, "router_token": rtoken, "name": name}

    if not KEEP_VMS:
        try:
            router_down()
        except Exception as e:  # noqa: BLE001
            log.warning("router_down failed: %s", e)
    try:
        admin.delete_router(router_id)
    except Exception as e:  # noqa: BLE001
        log.warning("delete_router(%s) failed: %s", router_id, e)


# ── function-scoped lifecycle ────────────────────────────────────────────


@pytest.fixture()
def client_vm() -> Client:
    mac = _suffix_mac()
    name = "client1"
    c = client_up(mac=mac, name=name)
    try:
        wait_for_client_dns(c, host="example.com", timeout_s=60)
        yield c
    finally:
        if not KEEP_VMS:
            try:
                client_down(name)
            except Exception as e:  # noqa: BLE001
                log.warning("client_down failed: %s", e)


@pytest.fixture()
def scratch_profile_and_device(admin, enrolled_router, client_vm):
    """Create a namespaced profile + device, assign one seeded app `blocked`
    and another `allowed` to it, and yield the relevant ids + hosts; clean
    up on teardown.

    Post-#764, extraBlocked / extraAllowed are no longer profile fields —
    they come from app_policy_assignments. Post-#1798, app *definitions* are
    template-authored ONLY — the arbitrary-host `POST /api/apps` create was
    retired, so this can no longer mint `example.com` / `example.org` apps.
    Instead it seeds the shipped templates (idempotent, admin-gated) and
    drives its two-leg block/allow assertion off two distinct *seeded* apps'
    real hosts. The block/allow contract is host-agnostic (block = HTTP/80
    DNAT to the block page on the resolved IPs; allow = carve-out / no
    over-block), so the assertion is unchanged — only the host source moves
    from a hand-minted app to the template catalog. Like example.com/.org,
    the chosen template apexes resolve + are reachable through qemu SLIRP v4
    NAT (see lib/app_seed.BLOCK_PREF / ALLOW_PREF).

    Apps surface used — all kept by #1798:
      POST   /api/apps/seed-from-templates          → seed catalog (idempotent)
      GET    /api/apps                               → read seeded apps + hosts
      PUT    /api/apps/{aid}/policy/{pid} {mode}     → assign block/allow
      DELETE /api/apps/{aid}/policy/{pid}            → detach (teardown)
    The shared seeded apps are NEVER deleted on teardown — only this run's
    namespaced profile/device + its policy assignments.
    """
    suffix = _suffix()

    # Seed (idempotent) then pick two distinct seeded apps for the two legs.
    admin.seed_apps_from_templates()
    blocked_app, allowed_app = pick_block_allow_apps(admin.list_apps())
    blocked_app_id = blocked_app["app"]["id"]
    allowed_app_id = allowed_app["app"]["id"]
    blocked_host = blocked_app["hosts"][0]
    allowed_host = allowed_app["hosts"][0]
    log.info(
        "gate3: block leg app=%s host=%s; allow leg app=%s host=%s",
        blocked_app["app"].get("slug"), blocked_host,
        allowed_app["app"].get("slug"), allowed_host,
    )

    pname = f"gate3-{suffix}"
    p = admin.create_profile(name=pname)
    pid = (p.get("profile") or p)["id"]

    admin.assign_app_policy(app_id=blocked_app_id, profile_id=pid, mode="blocked")
    admin.assign_app_policy(app_id=allowed_app_id, profile_id=pid, mode="allowed")

    mac = client_vm.mac
    # Baseline BEFORE the mutation: with ws the default the push lands in well
    # under a second, so a baseline captured afterwards would already hold the
    # new etag and the wait could never fire (#2608).
    baseline = policy_change_baseline(admin, enrolled_router["router_id"])
    admin.upsert_device(
        mac=mac,
        name=f"gate3-dev-{suffix}",
        profile_id=pid,
    )

    # Wait for the agent to pick up the snapshot that includes this device
    # + the app assignments. PolicyApply restarts dnsmasq (#341); re-gate
    # the client on a fresh resolver answer before the test starts
    # probing traffic.
    wait_for_etag_change(
        admin, enrolled_router["router_id"], baseline=baseline, timeout_s=180,
    )
    wait_for_client_dns(client_vm, host=blocked_host, timeout_s=30)

    yield {
        "profile_id": pid,
        "mac": mac,
        "blocked_host": blocked_host,
        "allowed_host": allowed_host,
        "blocked_app_id": blocked_app_id,
        "allowed_app_id": allowed_app_id,
    }

    # Teardown — detach this profile's policy assignments (the seeded apps
    # are shared and must survive), then delete the namespaced device +
    # profile. Order: device → assignments → profile.
    try:
        admin.delete_device(mac)
    except Exception as e:  # noqa: BLE001
        log.warning("delete_device(%s) failed: %s", mac, e)
    for aid in (blocked_app_id, allowed_app_id):
        try:
            admin.delete_app_policy(app_id=aid, profile_id=pid)
        except Exception as e:  # noqa: BLE001
            log.warning("delete_app_policy(app=%s, profile=%s) failed: %s", aid, pid, e)
    try:
        admin.delete_profile(pid)
    except Exception as e:  # noqa: BLE001
        log.warning("delete_profile(%s) failed: %s", pid, e)
