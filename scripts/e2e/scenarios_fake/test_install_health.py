"""Install-time health asserts against the booted router VM (Gate 2, #922).

The `router_session` fixture has already booted the router, run apk postinst,
enrolled, and confirmed at least one policy poll. Once we reach this point
the install must look healthy from the router's perspective.

Why cron specifically (#922): apk postinst (#869/#873) registers the
`wifihaven-update` cron entry; the agent self-heals it on startup
(#896/#899) precisely because installs have historically completed without
it, leaving a router that silently never auto-updates. If both paths fail
the only signal is a router that drifts further out of date every day —
exactly the kind of regression Gate 2 should fail loudly on.
"""
from __future__ import annotations

import pytest

from lib.vm import router_ssh

pytestmark = pytest.mark.install_health

CRONTAB_PATH = "/etc/crontabs/root"
EXPECTED_SCRIPT = "/usr/sbin/wifihaven-update"


def test_wifihaven_update_cron_entry_present(router):
    """After boot + first poll, /etc/crontabs/root must schedule wifihaven-update."""
    res = router_ssh(f"grep wifihaven-update {CRONTAB_PATH}", check=False)
    assert res.returncode == 0, (
        f"grep wifihaven-update {CRONTAB_PATH} exited {res.returncode}; "
        f"stdout={res.stdout!r} stderr={res.stderr!r}"
    )
    matched = res.stdout.strip()
    assert matched, f"empty grep match in {CRONTAB_PATH}"
    assert EXPECTED_SCRIPT in matched, (
        f"cron entry does not point at {EXPECTED_SCRIPT}: {matched!r}"
    )
