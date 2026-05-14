"""Thin wrapper around subprocess for shelling out to scripts/vm/* and ssh."""
from __future__ import annotations

import logging
import shlex
import subprocess
from dataclasses import dataclass

log = logging.getLogger(__name__)


@dataclass
class Result:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str

    def check(self) -> "Result":
        if self.returncode != 0:
            cmd = " ".join(shlex.quote(a) for a in self.args)
            raise RuntimeError(
                f"command failed (rc={self.returncode}): {cmd}\n"
                f"--stdout--\n{self.stdout}\n--stderr--\n{self.stderr}"
            )
        return self


def run(
    args: list[str],
    *,
    env: dict[str, str] | None = None,
    cwd: str | None = None,
    timeout: float | None = None,
    check: bool = True,
    input: str | None = None,
) -> Result:
    log.debug("run: %s", " ".join(shlex.quote(a) for a in args))
    proc = subprocess.run(
        args,
        env=env,
        cwd=cwd,
        timeout=timeout,
        input=input,
        capture_output=True,
        text=True,
    )
    res = Result(list(args), proc.returncode, proc.stdout, proc.stderr)
    if check:
        res.check()
    return res
