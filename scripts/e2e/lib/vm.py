"""Wrappers around scripts/vm/* for router + client VMs.

Owns no state of its own; all state lives in scripts/vm/.run/. Idempotency is
inherited from the underlying scripts (router-up is idempotent; client-up
requires the named slot to be free).
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass

from .paths import CLIENT_SSH_KEY, VM_DIR

# The router image bakes the same test keypair the client uses (see
# build-router-image.sh). Use it for the WAN-side SSH hostfwd so the
# orchestrator never depends on the host operator's ~/.ssh/.
ROUTER_SSH_KEY = CLIENT_SSH_KEY
from .sh import Result, run

log = logging.getLogger(__name__)

ROUTER_SSH_PORT = 2222
ROUTER_HOST = "127.0.0.1"
ROUTER_SSH_USER = "root"

# Router LuCI HTTP hostfwd. The orchestrator never uses LuCI but the underlying
# router-up.sh always sets up the forward. Default 8080 collides with a
# developer's docker-compose stack (and with our own e2e stack on certain
# layouts), so pin it to a high port. Override with FDNS_ROUTER_HTTP_PORT.
ROUTER_HTTP_PORT = int(os.environ.get("FDNS_ROUTER_HTTP_PORT", "18081"))

# QEMU SLIRP gateway as seen from inside the router VM. The router VM's WAN is
# user-mode networking, so it reaches the host (where the API stack runs) via
# 10.0.2.2. The orchestrator overrides familydns.api_url to point here.
ROUTER_HOST_GATEWAY = "10.0.2.2"


def _vm_script(name: str) -> str:
    return str(VM_DIR / name)


# ── Router ───────────────────────────────────────────────────────────────────


def router_up(*, image_path: str | None = None, wait_ssh_timeout: float = 180) -> Result:
    """Boot the router VM and wait for SSH to accept connections.

    `router-up.sh` daemonizes as soon as qemu launches, well before the guest
    finishes booting. The orchestrator's first action is always SSH, so block
    here until sshd answers — otherwise callers race the boot and see
    `Connection reset by peer`.
    """
    env = os.environ.copy()
    if image_path:
        env["FDNS_ROUTER_IMAGE_PATH"] = image_path
    env.setdefault("FDNS_ROUTER_HTTP_PORT", str(ROUTER_HTTP_PORT))
    env.setdefault("FDNS_ROUTER_SSH_PORT", str(ROUTER_SSH_PORT))
    res = run([_vm_script("router-up.sh")], env=env, timeout=300)
    _wait_for_router_ssh(timeout_s=wait_ssh_timeout)
    return res


def _wait_for_router_ssh(*, timeout_s: float = 240, interval_s: float = 3.0) -> None:
    """Poll the SSH hostfwd until sshd answers. First boot of the custom image
    takes ~30–60s; allow several minutes before giving up."""
    import socket as _socket
    import subprocess as _subprocess
    import time as _time

    deadline = _time.monotonic() + timeout_s
    last_err: str = ""
    while _time.monotonic() < deadline:
        # Cheap probe: is the port even open?
        with _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM) as s:
            s.settimeout(2)
            try:
                s.connect((ROUTER_HOST, ROUTER_SSH_PORT))
                port_open = True
            except OSError as e:
                port_open = False
                last_err = f"tcp probe: {e}"
        if port_open:
            # Try a real SSH command. Set ConnectTimeout so failures don't
            # hang past `interval_s`.
            args = [
                "ssh",
                "-p", str(ROUTER_SSH_PORT),
                "-i", str(ROUTER_SSH_KEY),
                "-o", "IdentitiesOnly=yes",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-o", "ConnectTimeout=5",
                f"{ROUTER_SSH_USER}@{ROUTER_HOST}",
                "true",
            ]
            try:
                proc = _subprocess.run(args, capture_output=True, text=True, timeout=10)
                if proc.returncode == 0:
                    return
                last_err = (proc.stderr or proc.stdout or "").strip()
            except _subprocess.TimeoutExpired:
                last_err = "ssh probe timed out"
        _time.sleep(interval_s)
    raise TimeoutError(f"router SSH never came up after {timeout_s}s (last: {last_err})")


def router_down() -> Result:
    """Cleanly shut down the router VM. Idempotent."""
    return run([_vm_script("router-down.sh")], check=False, timeout=60)


def router_snapshot(name: str) -> Result:
    return run([_vm_script("router-snapshot.sh"), name], timeout=60)


def router_restore(name: str) -> Result:
    return run([_vm_script("router-restore.sh"), name], timeout=60)


def router_ssh(cmd: str, *, timeout: float = 30, check: bool = True) -> Result:
    """Run a command on the router via the WAN-side hostfwd SSH port."""
    args = [
        "ssh",
        "-p", str(ROUTER_SSH_PORT),
        "-i", str(ROUTER_SSH_KEY),
        "-o", "IdentitiesOnly=yes",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "LogLevel=ERROR",
        f"{ROUTER_SSH_USER}@{ROUTER_HOST}",
        cmd,
    ]
    return run(args, timeout=timeout, check=check)


def router_serial_log(tail: int = 200) -> str:
    log_path = VM_DIR / ".run" / "router" / "console.log"
    if not log_path.exists():
        return "(no router serial log yet)"
    with log_path.open("rb") as f:
        try:
            f.seek(-tail * 200, 2)
        except OSError:
            f.seek(0)
        return f.read().decode("utf-8", errors="replace")


# ── Client ───────────────────────────────────────────────────────────────────


@dataclass
class Client:
    name: str
    mac: str
    ssh_port: int

    def ssh_args(self) -> list[str]:
        return [
            "ssh",
            "-p", str(self.ssh_port),
            "-i", str(CLIENT_SSH_KEY),
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            "-o", "LogLevel=ERROR",
            f"root@127.0.0.1",
        ]

    def serial_log(self, tail: int = 200) -> str:
        log_path = VM_DIR / ".run" / self.name / "console.log"
        if not log_path.exists():
            return f"(no client serial log for {self.name})"
        with log_path.open("rb") as f:
            try:
                f.seek(-tail * 200, 2)
            except OSError:
                f.seek(0)
            return f.read().decode("utf-8", errors="replace")


def client_up(*, mac: str, name: str = "client1", ssh_port: int = 2223) -> Client:
    args = [_vm_script("client-up.sh"), "--mac", mac, "--name", name, "--ssh-port", str(ssh_port)]
    run(args, timeout=180)
    c = Client(name=name, mac=mac, ssh_port=ssh_port)
    _wait_for_client_lan(c)
    return c


def _wait_for_client_lan(client: Client, *, timeout_s: float = 60, interval_s: float = 2.0) -> None:
    """Wait until the client's LAN interface (eth0) has a 192.168.100.x lease
    from the router and dnsmasq returns an answer for an external host.

    `client-up.sh` only gates on the SSH hostfwd (eth1) coming up — eth0 may
    still be waiting on DHCP from the router VM, which can lag several seconds
    behind the SSH path. Without this gate the first scenario after enrollment
    races and curl times out before its DNS resolves."""
    import time as _time
    deadline = _time.monotonic() + timeout_s
    last = ""
    probe_cmd = [
        "sh", "-c",
        "ip -4 addr show eth0 | grep -q '192\\.168\\.100\\.' && "
        "getent hosts example.com >/dev/null",
    ]
    while _time.monotonic() < deadline:
        res = client_exec(client, probe_cmd, timeout=10, check=False)
        if res.returncode == 0:
            return
        last = (res.stderr or res.stdout or "").strip()
        _time.sleep(interval_s)
    log.warning("client %s LAN/DNS not ready after %ss (last: %r)", client.name, timeout_s, last)


def client_down(name: str = "client1") -> Result:
    return run([_vm_script("client-down.sh"), "--name", name], check=False, timeout=60)


def client_exec(client: Client, cmd: list[str], *, timeout: float = 30, check: bool = True) -> Result:
    """Execute a command list on the client VM. Returns the remote exit code."""
    args = [_vm_script("client-exec.sh"), "--name", client.name, "--"] + cmd
    return run(args, timeout=timeout, check=check)
