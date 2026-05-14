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
from .sh import Result, run

log = logging.getLogger(__name__)

ROUTER_SSH_PORT = 2222
ROUTER_HOST = "127.0.0.1"
ROUTER_SSH_USER = "root"

# QEMU SLIRP gateway as seen from inside the router VM. The router VM's WAN is
# user-mode networking, so it reaches the host (where the API stack runs) via
# 10.0.2.2. The orchestrator overrides familydns.api_url to point here.
ROUTER_HOST_GATEWAY = "10.0.2.2"


def _vm_script(name: str) -> str:
    return str(VM_DIR / name)


# ── Router ───────────────────────────────────────────────────────────────────


def router_up(*, image_path: str | None = None) -> Result:
    """Boot the router VM. Idempotent (no-op if already running)."""
    env = os.environ.copy()
    if image_path:
        env["FDNS_ROUTER_IMAGE_PATH"] = image_path
    return run([_vm_script("router-up.sh")], env=env, timeout=300)


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
    return Client(name=name, mac=mac, ssh_port=ssh_port)


def client_down(name: str = "client1") -> Result:
    return run([_vm_script("client-down.sh"), "--name", name], check=False, timeout=60)


def client_exec(client: Client, cmd: list[str], *, timeout: float = 30, check: bool = True) -> Result:
    """Execute a command list on the client VM. Returns the remote exit code."""
    args = [_vm_script("client-exec.sh"), "--name", client.name, "--"] + cmd
    return run(args, timeout=timeout, check=check)
