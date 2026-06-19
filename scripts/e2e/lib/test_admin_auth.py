"""Integration cover for the #1790 self-healing admin auth helper.

Staging's Postgres resets every 30 days; on reset the admin user reverts to
the seeded `changeme` password (with mustChangePassword=true). The CI secret
holds the operator-chosen value, so every staging-gated job would 401 until
the operator intervened.

The helper (Python: `AdminAPI.login`; bash: `scripts/e2e/lib/admin-auth.sh`)
self-heals: try the stored password; on 401 try `changeme`; on success
immediately POST /api/auth/change-password back to the stored value and
re-login. The two helpers share the same algorithm so every staging-gated
job is covered by one logical handshake.

Run standalone:

    python3 -m pytest scripts/e2e/lib/test_admin_auth.py
"""
from __future__ import annotations

import http.server
import json
import os
import subprocess
import sys
import threading
from contextlib import contextmanager
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
from api_admin import AdminAPI  # noqa: E402


HERE = Path(__file__).parent
BASH_HELPER = HERE / "admin-auth.sh"

DEFAULT_PASSWORD = "changeme"
STORED_PASSWORD = "operator-chosen-pw-do-not-share"


class _FakeAuthAPI(http.server.BaseHTTPRequestHandler):
    """Tiny mock of the WifiHaven auth surface.

    Server-shared state lives on the HTTPServer instance:
      server.current_password    — the password the admin login accepts
      server.must_change         — whether logins return mustChangePassword=true
      server.token               — the JWT we hand back
      server.change_password_calls — for assertions
    """

    def log_message(self, fmt: str, *args) -> None:  # noqa: D401
        return

    def _read_json(self) -> dict:
        n = int(self.headers.get("content-length", "0"))
        return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}

    def _send(self, code: int, body: dict | None) -> None:
        payload = b"" if body is None else json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def do_POST(self) -> None:  # noqa: N802
        srv = self.server
        if self.path == "/api/auth/login":
            body = self._read_json()
            if body.get("password") == srv.current_password:
                self._send(200, {
                    "token": srv.token,
                    "role": "admin",
                    "username": body.get("username", "admin"),
                    "mustChangePassword": srv.must_change,
                })
            else:
                self._send(401, {"error": "Invalid credentials"})
            return
        if self.path == "/api/auth/change-password":
            auth = self.headers.get("authorization", "")
            if not auth.startswith("Bearer "):
                self._send(401, {"error": "missing bearer"})
                return
            body = self._read_json()
            srv.change_password_calls.append(body)
            if body.get("currentPassword") != srv.current_password:
                self._send(401, {"error": "Current password incorrect"})
                return
            srv.current_password = body["newPassword"]
            srv.must_change = False
            # #623: change-password returns JSON body, not empty.
            self._send(200, {"mustChangePassword": False})
            return
        self._send(404, {"error": "not found"})


@contextmanager
def _fake_api(*, current_password: str, must_change: bool = False):
    srv = http.server.HTTPServer(("127.0.0.1", 0), _FakeAuthAPI)
    srv.current_password = current_password
    srv.must_change = must_change
    srv.token = "fake-jwt-token-xyz"
    srv.change_password_calls = []
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        host, port = srv.server_address
        yield f"http://{host}:{port}", srv
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=2)


# ── Python helper ────────────────────────────────────────────────────────


def test_python_normal_day_single_login():
    """Stored password works → exactly one login call, no change-password."""
    with _fake_api(current_password=STORED_PASSWORD) as (url, srv):
        api = AdminAPI(url, username="admin", password=STORED_PASSWORD)
        token = api.login()
        assert token == srv.token
        assert srv.change_password_calls == []
        # Stored password unchanged.
        assert srv.current_password == STORED_PASSWORD


def test_python_post_reset_self_heals():
    """After DB reset (admin=changeme, must_change=true) the helper rotates
    the password back to the stored value and returns a JWT good for the
    rotated state."""
    with _fake_api(current_password=DEFAULT_PASSWORD, must_change=True) as (url, srv):
        api = AdminAPI(url, username="admin", password=STORED_PASSWORD)
        token = api.login()
        assert token == srv.token
        assert len(srv.change_password_calls) == 1
        call = srv.change_password_calls[0]
        assert call["currentPassword"] == DEFAULT_PASSWORD
        assert call["newPassword"] == STORED_PASSWORD
        # Server state is now back to the stored password.
        assert srv.current_password == STORED_PASSWORD


def test_python_hard_fail_when_neither_password_works():
    """Stored AND changeme both 401 → clear runtime error, no silent retry."""
    with _fake_api(current_password="some-other-pw") as (url, _srv):
        api = AdminAPI(url, username="admin", password=STORED_PASSWORD)
        with pytest.raises(RuntimeError) as ei:
            api.login()
        msg = str(ei.value)
        assert "neither" in msg.lower() or "default" in msg.lower() or "changeme" in msg.lower(), msg


# ── Bash helper ──────────────────────────────────────────────────────────


def _run_bash(url: str, password: str) -> subprocess.CompletedProcess:
    env = {
        **os.environ,
        "STAGING_API_URL": url,
        "STAGING_ADMIN_USER": "admin",
        "STAGING_ADMIN_PASSWORD": password,
    }
    return subprocess.run(
        ["bash", "-c", f"source {BASH_HELPER} && admin_login_self_heal"],
        env=env, capture_output=True, text=True, timeout=10,
    )


def test_bash_normal_day_single_login():
    with _fake_api(current_password=STORED_PASSWORD) as (url, srv):
        r = _run_bash(url, STORED_PASSWORD)
        assert r.returncode == 0, r.stderr
        assert r.stdout.strip() == srv.token
        assert srv.change_password_calls == []


def test_bash_post_reset_self_heals():
    with _fake_api(current_password=DEFAULT_PASSWORD, must_change=True) as (url, srv):
        r = _run_bash(url, STORED_PASSWORD)
        assert r.returncode == 0, r.stderr
        assert r.stdout.strip() == srv.token
        assert len(srv.change_password_calls) == 1
        assert srv.change_password_calls[0]["newPassword"] == STORED_PASSWORD
        assert srv.current_password == STORED_PASSWORD


def test_bash_hard_fail_when_neither_password_works():
    with _fake_api(current_password="some-other-pw") as (url, _srv):
        r = _run_bash(url, STORED_PASSWORD)
        assert r.returncode != 0
        assert "neither" in r.stderr.lower() or "changeme" in r.stderr.lower(), r.stderr


# ── Concurrent-gate race recovery (sibling rotated between our calls) ────


class _SiblingRaceHandler(_FakeAuthAPI):
    """Mock variant simulating a sibling gate that rotated 'changeme' to the
    stored password between our first changeme-call and our next call.

    Server state is set to (current_password=STORED_PASSWORD, must_change=False)
    BUT login returns 401 on the FIRST `changeme` attempt only (post-reset
    state we sampled), then 200 on the stored password — modelling a sibling
    that won the race after our initial stored-password login failed.

    Practically: stored-password login → 200 (sibling already rotated).
    changeme login → 401 (sibling already rotated).
    change-password with currentPassword=changeme → 401 (same reason).
    """


def test_bash_race_recovery_on_changeme_401():
    """Sibling rotated between our stored-login and our changeme-login.
    The helper must retry the stored password before declaring hard-fail."""
    # Start the server with the post-rotation state — stored works, changeme
    # does not. The test simulates: we already saw a 401 on stored (the
    # original reset state), then tried changeme and got 401 because a
    # sibling rotated. The helper should retry stored and succeed.
    # Force the first stored attempt to 401 by giving the mock a different
    # password initially, then swap mid-flight — easier: subclass the mock
    # to count requests and 401 the FIRST stored-password attempt only.
    state = {"first_stored_seen": False}

    class _Handler(_FakeAuthAPI):
        def do_POST(self):
            if self.path == "/api/auth/login":
                body = self._read_json()
                pw = body.get("password")
                if pw == STORED_PASSWORD and not state["first_stored_seen"]:
                    state["first_stored_seen"] = True
                    self._send(401, {"error": "Invalid credentials"})
                    return
                if pw == STORED_PASSWORD:
                    self._send(200, {
                        "token": self.server.token, "role": "admin",
                        "username": "admin", "mustChangePassword": False,
                    })
                    return
                self._send(401, {"error": "Invalid credentials"})
                return
            self._send(404, {})

    srv = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    srv.current_password = STORED_PASSWORD
    srv.must_change = False
    srv.token = "race-recovered-jwt"
    srv.change_password_calls = []
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        host, port = srv.server_address
        url = f"http://{host}:{port}"
        r = _run_bash(url, STORED_PASSWORD)
        assert r.returncode == 0, r.stderr
        assert r.stdout.strip() == srv.token
        # No change-password call: race-recovery skipped that step.
        assert srv.change_password_calls == []
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=2)


def test_python_race_recovery_on_changeme_401():
    """Python helper mirrors the bash race recovery."""
    state = {"first_stored_seen": False}

    class _Handler(_FakeAuthAPI):
        def do_POST(self):
            if self.path == "/api/auth/login":
                body = self._read_json()
                pw = body.get("password")
                if pw == STORED_PASSWORD and not state["first_stored_seen"]:
                    state["first_stored_seen"] = True
                    self._send(401, {"error": "Invalid credentials"})
                    return
                if pw == STORED_PASSWORD:
                    self._send(200, {
                        "token": self.server.token, "role": "admin",
                        "username": "admin", "mustChangePassword": False,
                    })
                    return
                self._send(401, {"error": "Invalid credentials"})
                return
            self._send(404, {})

    srv = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    srv.token = "race-recovered-jwt"
    srv.change_password_calls = []
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        host, port = srv.server_address
        api = AdminAPI(f"http://{host}:{port}", username="admin", password=STORED_PASSWORD)
        token = api.login()
        assert token == srv.token
        assert srv.change_password_calls == []
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=2)
