"""Pins the request shapes of the #2848 shared-device client methods on
AdminAPI (used by gate3/test_shared_device.py, #2879) against the routes in
api/src/routes/SharedDeviceRoutes.scala and DeviceRoutes' PATCH.

Gate 3 only runs post-merge, so a wrong path or body key here would otherwise
first surface as a red Master CD.

Run standalone:

    python3 -m pytest scripts/e2e/lib/test_api_admin_shared_devices.py
"""
from __future__ import annotations

import http.server
import json
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
from api_admin import AdminAPI  # noqa: E402


@pytest.fixture()
def recorder():
    seen: list[tuple[str, str, object]] = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def _handle(self):
            n = int(self.headers.get("content-length") or 0)
            raw = self.rfile.read(n) if n else b""
            seen.append((self.command, self.path, json.loads(raw) if raw else None))
            self.send_response(200)
            self.send_header("content-length", "0")
            self.end_headers()

        do_POST = do_PATCH = _handle

        def log_message(self, *_):
            pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    api = AdminAPI(f"http://127.0.0.1:{srv.server_address[1]}")
    api._token = "t"
    try:
        yield api, seen
    finally:
        srv.shutdown()


MAC = "02:e2:e3:aa:bb:cc"


def test_patch_device_sends_only_given_fields(recorder):
    api, seen = recorder
    api.patch_device(MAC, shared=True)
    api.patch_device(MAC, shared=False, profileId=7)
    assert seen == [
        ("PATCH", f"/api/devices/{MAC}", {"shared": True}),
        ("PATCH", f"/api/devices/{MAC}", {"shared": False, "profileId": 7}),
    ]


def test_check_in_and_out_paths_and_bodies(recorder):
    api, seen = recorder
    api.check_in_shared_device(MAC, profile_id=7)
    api.check_out_shared_device(MAC)
    assert seen == [
        ("POST", f"/api/shared-devices/{MAC}/check-in", {"profileId": 7}),
        ("POST", f"/api/shared-devices/{MAC}/check-out", None),
    ]
