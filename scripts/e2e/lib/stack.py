"""API stack lifecycle (docker compose) + diagnostic log capture.

The orchestrator needs FAMILYDNS_DEBUG=1 so the /api/debug/* endpoints are
mounted (loopback-only by design). We bring up our own compose project on a
dedicated port so we don't collide with a developer's running stack.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path

from .paths import REPO_ROOT
from .sh import run

log = logging.getLogger(__name__)

DEFAULT_PROJECT = "familydns-e2e-vm"
DEFAULT_API_PORT = 18080


@dataclass
class StackHandle:
    project: str
    api_port: int
    base_url: str
    compose_files: list[Path]
    env: dict[str, str]

    def teardown(self) -> None:
        log.info("tearing down compose project %s", self.project)
        run(
            self._compose_cmd() + ["down", "-v", "--remove-orphans"],
            env=self.env,
            check=False,
            timeout=120,
        )

    def logs(self, service: str = "", tail: int = 200) -> str:
        args = self._compose_cmd() + ["logs", "--tail", str(tail)]
        if service:
            args.append(service)
        res = run(args, env=self.env, check=False, timeout=30)
        return res.stdout + res.stderr

    def _compose_cmd(self) -> list[str]:
        cmd = ["docker", "compose", "-p", self.project]
        for cf in self.compose_files:
            cmd += ["-f", str(cf)]
        return cmd


def bring_up(
    *,
    project: str = DEFAULT_PROJECT,
    api_port: int = DEFAULT_API_PORT,
    rebuild: bool = False,
) -> StackHandle:
    """Start the API stack with debug endpoints enabled. Idempotent: if the
    compose project is already running and healthy, just returns a handle."""
    base_url = f"http://127.0.0.1:{api_port}"
    compose_main = REPO_ROOT / "docker" / "docker-compose.yml"
    if not compose_main.exists():
        # Some checkouts keep it at repo root.
        compose_main = REPO_ROOT / "docker-compose.yml"
    if not compose_main.exists():
        raise FileNotFoundError("could not locate docker-compose.yml")

    # Inline override: rebind API host port + flip debug on.
    override_path = REPO_ROOT / ".e2e-vm-artifacts" / "docker-compose.override.yml"
    override_path.parent.mkdir(parents=True, exist_ok=True)
    override_path.write_text(
        "services:\n"
        "  api:\n"
        f"    ports:\n      - \"127.0.0.1:{api_port}:8080\"\n"
        "    environment:\n"
        "      FAMILYDNS_DEBUG: \"1\"\n"
        # Bind on all interfaces inside the docker network so the router VM
        # (reaching us via SLIRP host gateway 10.0.2.2) can hit the API too.
        # docker compose already exposes ${api_port} on 0.0.0.0 of the host,
        # which is what 10.0.2.2 maps to from the guest.
    )

    env = os.environ.copy()
    handle = StackHandle(
        project=project,
        api_port=api_port,
        base_url=base_url,
        compose_files=[compose_main, override_path],
        env=env,
    )

    args = handle._compose_cmd() + ["up", "-d", "--wait"]
    if rebuild:
        args.append("--build")
    log.info("compose up: project=%s port=%s rebuild=%s", project, api_port, rebuild)
    # `--wait` already gates on the api service's healthcheck (login probe),
    # so the API is reachable on api_port the moment this returns.
    run(args, env=env, timeout=600).check()
    log.info("API stack ready at %s", base_url)
    return handle
