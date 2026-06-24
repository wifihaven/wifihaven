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


def http_get(
    client: Client, url: str, *, timeout_s: int = 8, ipv4_only: bool = False
) -> HttpProbe:
    """Curl a URL from the client. Captures status code + first 256 bytes.

    `ipv4_only=True` adds curl's `-4` so the request resolves and connects over
    IPv4 only. Use this when the test set up and asserted an IPv4-family carve /
    drop set (e.g. ea_/eb_/bl_) and must exercise THAT family end-to-end: a
    dual-stack fixture host (one with both an A and an AAAA, e.g. the #1677
    e2e-edge leaf) would otherwise let curl's happy-eyeballs prefer the v6
    address and probe the v6 chain — a different set the test never populated
    (see test_cname_direct_requery for the concrete #1929 trap).
    """
    family = "-4 " if ipv4_only else ""
    cmd = [
        "sh", "-c",
        # `-w` writes status code on its own line at the end of stdout. We pipe
        # the body through head to bound size. `--max-time` prevents hangs.
        f"curl {family}-s --max-time {timeout_s} -o - -w '\\nHTTPCODE:%{{http_code}}\\n' "
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


def tls_clienthello(client: Client, sni: str, ip: str, *, timeout_s: int = 5) -> Result:
    """Emit a TLS ClientHello carrying `sni` to `ip:443` from the client — WITHOUT
    a DNS lookup for `sni` (#573).

    `curl --resolve <sni>:443:<ip>` pins the connection to `ip` so curl never
    queries dnsmasq for `sni`; the ClientHello with `server_name=sni` is sent on
    br-lan immediately after the TCP handshake completes. `-k` ignores the bogus
    cert and `--max-time` bounds the (expected-to-fail) request. The HTTPS request
    will not return a real body — `ip` is a throwaway listener — but that does not
    matter: the proof is that the ClientHello crossed br-lan, where the SNI sidecar
    captures it. Success is NOT required; emission is. Returns the raw Result for
    diagnostics.
    """
    url = f"https://{sni}/"
    return client_exec(
        client,
        [
            "sh", "-c",
            f"curl -k -s --max-time {timeout_s} "
            f"--resolve {shell_quote(f'{sni}:443:{ip}')} "
            f"-o /dev/null {shell_quote(url)} || true",
        ],
        timeout=timeout_s + 10,
        check=False,
    )


def shell_quote(s: str) -> str:
    import shlex
    return shlex.quote(s)
