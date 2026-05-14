"""Drive traffic from the client VM. All probes go through eth0 (LAN-side),
which DHCPs from the router VM, so the router resolves and policy-checks
every request.

Returned objects expose:
  .ok          — True if the probe completed in the way the caller asked for
  .http_code   — HTTP status as observed by curl, or None
  .body        — first 256 bytes of the response body (string)
  .stderr      — curl stderr (useful for connection-reset / DNS-failure cases)
"""
from __future__ import annotations

from dataclasses import dataclass

from .sh import Result
from .vm import Client, client_exec


@dataclass
class HttpProbe:
    ok: bool
    http_code: int | None
    body: str
    stderr: str
    raw: Result


def http_get(client: Client, url: str, *, timeout_s: int = 8) -> HttpProbe:
    """Curl a URL from the client. Captures status code + first 256 bytes."""
    cmd = [
        "sh", "-c",
        # `-w` writes status code on its own line at the end of stdout. We pipe
        # the body through head to bound size. `--max-time` prevents hangs.
        f"curl -s --max-time {timeout_s} -o - -w '\\nHTTPCODE:%{{http_code}}\\n' "
        f"{shell_quote(url)} | head -c 4096",
    ]
    res = client_exec(client, cmd, timeout=timeout_s + 10, check=False)
    body = res.stdout
    code: int | None = None
    for line in body.splitlines():
        if line.startswith("HTTPCODE:"):
            try:
                code = int(line.split(":", 1)[1].strip() or "0") or None
            except ValueError:
                code = None
    # Trim the marker line out of the body for readability.
    body_clean = "\n".join(l for l in body.splitlines() if not l.startswith("HTTPCODE:"))
    return HttpProbe(
        ok=(res.returncode == 0 and code is not None),
        http_code=code,
        body=body_clean[:1024],
        stderr=res.stderr,
        raw=res,
    )


def dns_query(client: Client, host: str, *, timeout_s: int = 5) -> Result:
    """Run dig on the client. Always uses the router-supplied resolver."""
    return client_exec(
        client,
        ["dig", "+time=2", "+tries=1", "+short", host],
        timeout=timeout_s + 5,
        check=False,
    )


def shell_quote(s: str) -> str:
    import shlex
    return shlex.quote(s)
