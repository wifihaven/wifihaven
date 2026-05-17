"""API stack lifecycle (docker compose) + diagnostic log capture.

The orchestrator needs WIFIHAVEN_DEBUG=1 so the /api/debug/* endpoints are
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
    # !reset replaces (rather than merges) the base-file `ports` lists so a
    # developer's existing stack on 8080/5433 doesn't clash with the e2e stack.
    # Postgres is only reached by the api over the compose network — no need
    # to expose it on the host.
    override_path.write_text(
        "services:\n"
        "  postgres:\n"
        "    ports: !reset []\n"
        "  api:\n"
        "    ports: !override\n"
        f"      - \"0.0.0.0:{api_port}:8080\"\n"
        "    environment:\n"
        "      WIFIHAVEN_DEBUG: \"1\"\n"
        # The compose stack ships a `fake-router` service that posts 2 fake
        # connection events per second under its own router_id. In e2e it
        # drowns our real router's events out of any reasonable
        # `/api/debug/events?limit=...` window, so disable it. Override the
        # command to a long-running no-op and zero out its healthcheck.
        "  fake-router:\n"
        "    command: [\"sleep\", \"infinity\"]\n"
        "    healthcheck: !override\n"
        "      test: [\"CMD\", \"true\"]\n"
        "      interval: 10s\n"
        "      timeout: 2s\n"
        "      retries: 1\n"
        # Bind on 0.0.0.0 of the host so the router VM (reaching us via SLIRP
        # host gateway 10.0.2.2) can hit the API too.
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
