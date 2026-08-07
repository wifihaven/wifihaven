"""In-memory state container for the fake API.

aiohttp runs handlers on a single event loop, so no locking is needed —
tests poke /test/* between agent requests, not concurrently with them.
"""

from __future__ import annotations

import copy
import uuid
from dataclasses import dataclass, field
from typing import Any

from .fixtures import load_initial_snapshot


@dataclass
class RegisterRecord:
    body: dict[str, Any]
    headers: dict[str, str]


@dataclass
class EventRecord:
    id: int
    body: dict[str, Any]


@dataclass
class UsageRecord:
    id: int
    body: dict[str, Any]


@dataclass
class PolicyFetchRecord:
    id: int
    if_none_match: str | None
    since_query: str | None
    served_etag: str
    status: int  # 200 or 304
    # #2608: which transport DELIVERED this snapshot — "http" for a
    # GET /api/router/policy poll, "ws" for a pushed `policy` frame. Once ws is
    # the shipped default the poll goes dormant on a healthy link (#2037), so a
    # scenario that waits for "the agent has the current etag" must be satisfied
    # by either transport. Keeping both in ONE list is what makes
    # `wait_for_etag_served` transport-agnostic instead of needing a parallel
    # ws-only observable that would then have to be kept in sync.
    transport: str = "http"


@dataclass
class State:
    initial_snapshot: dict[str, Any]
    snapshot: dict[str, Any]
    router_id: str
    router_token: str
    registers: list[RegisterRecord] = field(default_factory=list)
    events: list[EventRecord] = field(default_factory=list)
    usage: list[UsageRecord] = field(default_factory=list)
    policy_fetches: list[PolicyFetchRecord] = field(default_factory=list)
    clock_base: str | None = None
    # blocklist content keyed by blocklist id: id → newline-delimited host list.
    # Served at GET /api/blocklists/<id>. Populated by POST /test/blocklist.
    # Persists across resets so tests don't have to re-register them after each
    # router restore; a test that needs a clean slate can POST an empty body.
    blocklists: dict[str, str] = field(default_factory=dict)
    # #1939: live websocket connections (aiohttp WebSocketResponse objects) the
    # agent's wifihaven-ws sidecar has opened against GET /api/router/ws. The fake
    # pushes a `policy` frame to each on connect and on every snapshot change, the
    # server-side end of the #1849 push path. Not a dataclass-copyable value —
    # never deep-copied; managed purely by register_ws/deregister_ws.
    ws_connections: set = field(default_factory=set)
    # #1939: cumulative count of `policy` frames the fake has successfully sent
    # over ws (on-connect + on-change). Lets a scenario assert "the server pushed"
    # from the fake side, complementing the router-side receive observables.
    ws_policy_frames_sent: int = 0
    _next_event_id: int = 1
    _next_usage_id: int = 1
    _next_policy_fetch_id: int = 1

    @classmethod
    def fresh(cls) -> "State":
        snap = load_initial_snapshot()
        st = cls(
            initial_snapshot=copy.deepcopy(snap),
            snapshot=copy.deepcopy(snap),
            router_id=str(uuid.uuid4()),
            router_token="rt_" + uuid.uuid4().hex,
        )
        st._seed_initial_blocklists()
        return st

    def _seed_initial_blocklists(self) -> None:
        """Seed empty in-memory content for every blocklist id the initial
        snapshot references (#2034).

        The base/initial snapshot (the contract golden) carries category
        blocklists (e.g. ``ads``, ``adult``) whose ``GET /api/blocklists/<id>``
        would otherwise 404 in fake mode — the ids are never registered, and
        ``adult`` originally pointed at external egress (now localized in
        ``fixtures._localize_blocklist_urls``). An unregistered id makes the
        agent's base-session blocklist fetch fail (``status=404``), polluting the
        logs and coupling the suite to external egress. Seeding empty content
        makes the fetch deterministically succeed (200, zero members) without
        enforcing anything; a scenario that needs real members registers its own
        via ``POST /test/blocklist``. ``setdefault`` so a pre-registered id is
        never clobbered.
        """
        for bl_id in (self.initial_snapshot.get("blocklists") or {}):
            self.blocklists.setdefault(bl_id, "")

    @property
    def etag(self) -> str:
        # Snapshot JSON's `etag` field is the canonical wire value (e.g.
        # `"sha256:abc..."` — quotes included). The agent sends it back
        # verbatim in If-None-Match / ?since=.
        return self.snapshot.get("etag", "")

    def replace_snapshot(self, new_snapshot: dict[str, Any]) -> None:
        self.snapshot = copy.deepcopy(new_snapshot)

    def record_register(self, body: dict[str, Any], headers: dict[str, str]) -> None:
        self.registers.append(RegisterRecord(body=body, headers=headers))

    def record_event_batch(self, body: dict[str, Any]) -> int:
        rec_id = self._next_event_id
        self._next_event_id += 1
        self.events.append(EventRecord(id=rec_id, body=body))
        return rec_id

    def record_policy_fetch(
        self,
        *,
        if_none_match: str | None,
        since_query: str | None,
        served_etag: str,
        status: int,
        transport: str = "http",
    ) -> int:
        rec_id = self._next_policy_fetch_id
        self._next_policy_fetch_id += 1
        self.policy_fetches.append(
            PolicyFetchRecord(
                id=rec_id,
                if_none_match=if_none_match,
                since_query=since_query,
                served_etag=served_etag,
                status=status,
                transport=transport,
            )
        )
        return rec_id

    def record_policy_push(self, *, served_etag: str) -> int:
        """#2608: record a snapshot DELIVERED over ws, in the same list as polls.

        The real API has the equivalent: RouterWsRegistry stamps `routers
        .last_etag` on a successful push, so "which policy version does this
        router have" is answerable regardless of transport.
        """
        return self.record_policy_fetch(
            if_none_match=None,
            since_query=None,
            served_etag=served_etag,
            status=200,
            transport="ws",
        )

    def record_usage(self, body: dict[str, Any]) -> int:
        rec_id = self._next_usage_id
        self._next_usage_id += 1
        self.usage.append(UsageRecord(id=rec_id, body=body))
        return rec_id

    def set_blocklist(self, id: str, content: str) -> None:
        """Set the content for a blocklist (newline-delimited host list).

        Content is intentionally preserved across `reset()` calls so test
        scenarios don't have to re-register blocklist bodies after each
        per-function router restore. A scenario that needs an empty slate can
        call `set_blocklist(id, "")` explicitly.
        """
        self.blocklists[id] = content

    def get_blocklist(self, id: str) -> str | None:
        """Return the content for a blocklist id, or None if not registered."""
        return self.blocklists.get(id)

    # ── #1939: ws connection registry + policy-push bookkeeping ──────────────

    def register_ws(self, ws) -> None:
        """Record a freshly-upgraded /api/router/ws channel."""
        self.ws_connections.add(ws)

    def deregister_ws(self, ws) -> None:
        """Drop a closed/closing /api/router/ws channel (idempotent)."""
        self.ws_connections.discard(ws)

    def note_policy_frame_sent(self) -> None:
        """Count one `policy` frame the fake successfully pushed over ws."""
        self.ws_policy_frames_sent += 1

    def reset(self) -> None:
        self.snapshot = copy.deepcopy(self.initial_snapshot)
        self.registers.clear()
        self.events.clear()
        self.usage.clear()
        self.policy_fetches.clear()
        self._next_event_id = 1
        self._next_usage_id = 1
        self._next_policy_fetch_id = 1
        self.clock_base = None
        # #1939: drop ws bookkeeping. The per-function `router` fixture restores
        # the VM to the base (ws-OFF) snapshot before each scenario, so any
        # channels left here are dead sockets from a prior ws-enabled scenario;
        # clearing keeps the connection count + push tally honest for the next
        # test. We do NOT close them — the restore already severed the socket and
        # the handler's finally deregisters on its own.
        self.ws_connections.clear()
        self.ws_policy_frames_sent = 0
        # Note: blocklists are intentionally NOT cleared here — see set_blocklist.
