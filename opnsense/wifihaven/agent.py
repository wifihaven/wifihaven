"""agent.py — main daemon skeleton for the wifihaven OPNsense agent.

Wires together:
  - pflog watcher  → connection_attempt events → POST /api/router/events
  - unbound watcher → populates HostnameCache for hostname attribution
  - policy polling + config rendering  (TODO(#112): policy polling + pf/Unbound config rendering)
  - usage reporting                    (TODO(#113): usage reporting POST /api/router/usage)

Provides:
  - Batcher: size- and time-triggered event batcher
  - post_with_retry: HTTP POST with exponential back-off
  - main(): entry point, reads config, starts threads
"""

import json
import sys
import threading
import time
import urllib.request
import urllib.error
from typing import Callable, List, Optional


# ---------------------------------------------------------------------------
# Batcher
# ---------------------------------------------------------------------------

class Batcher:
    """Accumulates events and flushes them in batches.

    Flushes automatically when:
      - add() causes the buffer to reach max_size, OR
      - tick(now) is called and flush_interval seconds have elapsed since the
        last flush.
    flush() drains whatever is pending immediately.

    flush_fn(events: list) is called with the accumulated events.
    """

    def __init__(
        self,
        max_size: int,
        flush_interval: float,
        flush_fn: Callable[[list], None],
        _start_now: float = 0,
    ) -> None:
        self._max_size = max_size
        self._flush_interval = flush_interval
        self._flush_fn = flush_fn
        self._buf: list = []
        # _last_flush defaults to 0 so tests can use simple absolute values.
        self._last_flush: float = _start_now

    def add(self, event: dict) -> None:
        self._buf.append(event)
        if len(self._buf) >= self._max_size:
            self.flush()

    def flush(self) -> None:
        if not self._buf:
            return
        to_send = self._buf
        self._buf = []
        self._flush_fn(to_send)

    def tick(self, now: Optional[float] = None) -> None:
        """Flush if flush_interval has elapsed since the last flush.

        now is injectable (absolute seconds, same scale as _last_flush) so
        tests can advance time without sleeping.  Production callers pass
        time.monotonic().
        """
        if now is None:
            now = time.monotonic()
        if (now - self._last_flush) >= self._flush_interval:
            self._last_flush = now
            self.flush()


# ---------------------------------------------------------------------------
# HTTP POST with exponential back-off retry
# ---------------------------------------------------------------------------

def post_with_retry(
    url: str,
    body: str,
    max_retries: int,
    base_delay: float,
    post_fn: Optional[Callable] = None,
    sleep_fn: Optional[Callable] = None,
) -> bool:
    """POST body to url, retrying on 5xx / connection errors.

    post_fn(url, body) -> (status_code | None, body_str)
      - status_code is None on connection failure.
    sleep_fn(seconds) — injectable for tests (default: time.sleep).

    Returns True on 2xx, False after max_retries are exhausted or on 4xx
    (client errors are not retryable).
    """
    if sleep_fn is None:
        sleep_fn = time.sleep

    if post_fn is None:
        def post_fn(u: str, b: str):  # type: ignore[misc]
            return _http_post(u, b)

    delay = base_delay
    for attempt in range(1, max_retries + 1):
        status, _ = post_fn(url, body)
        if status is not None and 200 <= status < 300:
            return True
        if status is not None and 400 <= status < 500:
            return False
        # 5xx or connection failure — retry with back-off.
        if attempt < max_retries:
            sleep_fn(delay)
            delay *= 2

    return False


def _http_post(url: str, body: str) -> tuple:
    """Default HTTP POST implementation using urllib (available on all Python installs)."""
    try:
        req = urllib.request.Request(
            url,
            data=body.encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, ""
    except (urllib.error.URLError, OSError):
        return None, ""


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def _make_post_fn(api_url: str, router_token: str, router_id: str):
    events_url = f"{api_url}/api/router/events"

    def post_fn(url: str, body: str):
        try:
            req = urllib.request.Request(
                url,
                data=body.encode(),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {router_token}",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as exc:
            return exc.code, ""
        except (urllib.error.URLError, OSError):
            return None, ""

    return post_fn, events_url


def main() -> None:
    import configparser
    import os

    config_path = os.environ.get("WIFIHAVEN_CONFIG", "/usr/local/etc/wifihaven.conf")
    cfg = configparser.ConfigParser()
    cfg.read(config_path)
    sec = cfg["wifihaven"] if "wifihaven" in cfg else {}

    api_url = sec.get("api_url", "http://192.168.1.1:8080")
    router_token = sec.get("router_token", "")
    router_id = sec.get("router_id", "")
    lan_prefix = sec.get("lan_prefix", "192.168.1.")
    max_batch = int(sec.get("event_batch_size", "50"))
    flush_interval = int(sec.get("event_flush_interval", "10"))
    unbound_log = sec.get("unbound_log", "/var/log/unbound/unbound.log")

    # TODO(#111): enrollment — exchange enrollmentToken for router_token/router_id automatically
    if not router_token:
        print("[wifihaven] router_token not set — run enrollment first", file=sys.stderr)
        sys.exit(1)
    if not router_id:
        print("[wifihaven] router_id not set — run enrollment first", file=sys.stderr)
        sys.exit(1)

    from wifihaven.unbound import HostnameCache, watch as unbound_watch
    from wifihaven.pflog import watch as pflog_watch

    hostname_cache = HostnameCache(maxsize=4096)
    post_fn, events_url = _make_post_fn(api_url, router_token, router_id)

    def flush_fn(events: list) -> None:
        payload = json.dumps({"routerId": router_id, "events": events})
        ok = post_with_retry(events_url, payload, 3, 2, post_fn)
        if not ok:
            print(
                "[wifihaven] pflog: failed to POST events batch after retries, dropping",
                file=sys.stderr,
            )

    batcher = Batcher(max_batch, flush_interval, flush_fn)

    unbound_cfg = {
        "log_path": unbound_log,
        "hostname_cache": hostname_cache,
    }
    pflog_cfg = {
        "router_id": router_id,
        "api_url": api_url,
        "router_token": router_token,
        "hostname_cache": hostname_cache,
        "batcher": batcher,
        "lan_prefix": lan_prefix,
    }

    t_unbound = threading.Thread(target=unbound_watch, args=(unbound_cfg,), daemon=True)
    t_unbound.start()

    # pflog watcher runs in the main thread (blocking).
    pflog_watch(pflog_cfg)


if __name__ == "__main__":
    main()
