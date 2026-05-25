"""Unit tests for the free-port allocator used by the fake-mode harness (#902).

The allocator's only job is to hand out a host port that's free *right now*.
We check it (a) returns something bindable, (b) doesn't hand out the same
port twice in a tight loop (kernel will rotate ephemeral ports), and (c)
returns a port in the user/ephemeral range.
"""
from __future__ import annotations

import socket

from .conftest import alloc_free_port


def test_alloc_free_port_returns_bindable_port() -> None:
    port = alloc_free_port()
    # Must be bindable immediately after release — proves nothing held it
    # by accident and that the kernel cleaned up the SO_REUSEADDR-less
    # close cleanly.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", port))


def test_alloc_free_port_in_ephemeral_range() -> None:
    port = alloc_free_port()
    # Linux ip_local_port_range defaults to 32768..60999; macOS uses a
    # higher window. Either way the kernel hands out something > 1024.
    assert port > 1024


def test_alloc_free_port_does_not_repeat_in_100_calls() -> None:
    seen: set[int] = set()
    for _ in range(100):
        seen.add(alloc_free_port())
    # The kernel rotates ephemeral ports per bind, so a tight loop should
    # see well over half unique ports. Not strict — under extreme port
    # pressure the kernel can repeat. Sanity, not contract.
    assert len(seen) >= 50, f"only {len(seen)} unique ports across 100 calls"
