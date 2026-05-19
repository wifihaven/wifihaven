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
    _next_event_id: int = 1
    _next_usage_id: int = 1
    _next_policy_fetch_id: int = 1

    @classmethod
    def fresh(cls) -> "State":
        snap = load_initial_snapshot()
        return cls(
            initial_snapshot=copy.deepcopy(snap),
            snapshot=copy.deepcopy(snap),
            router_id=str(uuid.uuid4()),
            router_token="rt_" + uuid.uuid4().hex,
        )

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
            )
        )
        return rec_id

    def record_usage(self, body: dict[str, Any]) -> int:
        rec_id = self._next_usage_id
        self._next_usage_id += 1
        self.usage.append(UsageRecord(id=rec_id, body=body))
        return rec_id

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
