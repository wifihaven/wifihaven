"""Unit cover for the #1846 ws e2e client's tolerance of #1849 push frames (#1951).

`ws_send.py` drives the API's `/api/router/ws` endpoint for the e2e parity check:
it sends N `{op,payload,seq}` frames and collects the server's `ack` replies. After
#1849 the server PUSHES an unsolicited `{op:"policy",...}` frame on connect (and on
snapshot change). The old client read exactly one reply per sent frame, so an
interleaved push displaced the 1:1 mapping and dropped the last frame's ack — the
symptom in #1951.

This pins the fixed behavior: regardless of WHERE the server interleaves the
push, `send_frames` keeps reading until every sent frame has an `ack`, and surfaces
the push frame(s) in the returned list so the caller can ignore (and optionally
assert on) them.

Run standalone:

    python3 -m pytest scripts/e2e/lib/test_ws_send.py
"""
from __future__ import annotations

import base64
import hashlib
import json
import socket
import struct
import sys
import threading
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ws_send import send_frames  # noqa: E402

_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
_OPCODE_TEXT = 0x1
_OPCODE_CLOSE = 0x8


def _accept_key(client_key: str) -> str:
    digest = hashlib.sha1((client_key + _WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def _server_send_text(conn: socket.socket, text: str) -> None:
    # Server→client frames are NOT masked (RFC 6455 §5.1).
    payload = text.encode("utf-8")
    n = len(payload)
    header = bytearray([0x80 | _OPCODE_TEXT])
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header += struct.pack(">H", n)
    else:
        header.append(127)
        header += struct.pack(">Q", n)
    conn.sendall(bytes(header) + payload)


def _server_recv_frame(conn: socket.socket, buf: bytearray) -> tuple[int, str]:
    def need(total: int) -> None:
        while len(buf) < total:
            chunk = conn.recv(4096)
            if not chunk:
                raise RuntimeError("client closed mid-frame")
            buf.extend(chunk)

    need(2)
    opcode = buf[0] & 0x0F
    b1 = buf[1]
    masked = b1 & 0x80
    length = b1 & 0x7F
    idx = 2
    if length == 126:
        need(4)
        length = struct.unpack(">H", bytes(buf[2:4]))[0]
        idx = 4
    elif length == 127:
        need(10)
        length = struct.unpack(">Q", bytes(buf[2:10]))[0]
        idx = 10
    mask = b""
    if masked:
        need(idx + 4)
        mask = bytes(buf[idx:idx + 4])
        idx += 4
    need(idx + length)
    data = bytearray(buf[idx:idx + length])
    if masked:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    del buf[:idx + length]
    text = "" if opcode == _OPCODE_CLOSE else bytes(data).decode("utf-8")
    return opcode, text


def _handshake(conn: socket.socket) -> bytearray:
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        chunk = conn.recv(4096)
        if not chunk:
            raise RuntimeError("client closed during handshake")
        buf += chunk
    head, _, rest = bytes(buf).partition(b"\r\n\r\n")
    key = ""
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"sec-websocket-key:"):
            key = line.split(b":", 1)[1].strip().decode("ascii")
    resp = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {_accept_key(key)}\r\n\r\n"
    )
    conn.sendall(resp.encode("ascii"))
    return bytearray(rest)


@contextmanager
def fake_ws_server(push_position: int):
    """A one-shot ws server that acks every frame and injects one `policy` push.

    `push_position` controls when the unsolicited push is sent relative to the
    acks: 0 → before any ack (the #1951 reproduction), N → after the Nth ack,
    so the test exercises the push landing first, in the middle, and last.
    """
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def serve() -> None:
        conn, _ = srv.accept()
        with conn:
            buf = _handshake(conn)
            policy = json.dumps({"op": "policy", "payload": {"profiles": {}, "devices": []}})
            if push_position == 0:
                _server_send_text(conn, policy)
            acked = 0
            while True:
                try:
                    opcode, text = _server_recv_frame(conn, buf)
                except RuntimeError:
                    break
                if opcode == _OPCODE_CLOSE:
                    break
                frame = json.loads(text)
                _server_send_text(
                    conn,
                    json.dumps(
                        {
                            "op": "ack",
                            "payload": {"op": frame["op"], "seq": frame["seq"], "status": "ok"},
                        }
                    ),
                )
                acked += 1
                if acked == push_position:
                    _server_send_text(conn, policy)

    t = threading.Thread(target=serve, daemon=True)
    t.start()
    try:
        yield f"ws://127.0.0.1:{port}/api/router/ws"
    finally:
        srv.close()


_FRAMES = [
    {"op": "events", "seq": 1, "payload": {}},
    {"op": "usage", "seq": 2, "payload": {}},
    {"op": "metrics", "seq": 3, "payload": {}},
]


def _acks_by_seq(replies: list[dict]) -> dict[int, dict]:
    out = {}
    for r in replies:
        if r.get("op") == "ack":
            out[(r.get("payload") or {}).get("seq")] = r["payload"]
    return out


def _run_with_push_at(position: int) -> list[dict]:
    with fake_ws_server(push_position=position) as url:
        return send_frames(url, token="t", frames=_FRAMES, timeout=10.0)


def test_every_sent_frame_is_acked_regardless_of_push_position() -> None:
    # The push landing first (0 == the #1951 repro), middle, or last must never
    # drop an ack: all three seqs are present and ok in every interleaving.
    for position in (0, 1, 2, 3):
        replies = _run_with_push_at(position)
        acks = _acks_by_seq(replies)
        assert set(acks) == {1, 2, 3}, f"missing acks at push_position={position}: {acks}"
        assert all(a["status"] == "ok" for a in acks.values())


def test_policy_push_is_surfaced_not_swallowed() -> None:
    # The push frame is returned to the caller (so the e2e script can ignore it
    # for the ack check and optionally assert it WAS observed).
    replies = _run_with_push_at(0)
    pushes = [r for r in replies if r.get("op") == "policy"]
    assert len(pushes) == 1, f"expected exactly one policy push, got {pushes}"


def test_no_extra_acks_beyond_sent_frames() -> None:
    # Tolerating pushes must not weaken parity: exactly one ack per sent frame.
    replies = _run_with_push_at(0)
    acks = [r for r in replies if r.get("op") == "ack"]
    assert len(acks) == len(_FRAMES)


if __name__ == "__main__":
    sys.exit(__import__("pytest").main([__file__, "-v"]))
