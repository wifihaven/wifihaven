#!/usr/bin/env python3
"""Stdlib-only websocket client for the router→API transport e2e (#1846).

Drives the API's `/api/router/ws` endpoint the way a fake router agent would,
using ONLY the Python standard library (socket + ssl + hashlib) so it runs in
the bare e2e shells (`scripts/e2e-router.sh`) against both the in-docker stack
(`ws://`) and deployed staging (`wss://`) without any pip install.

Usage:
    echo '[{"op":"events","seq":1,"payload":{…}}, …]' \
        | ws_send.py --url ws://127.0.0.1:8080/api/router/ws --token "$RTOK"

Reads a JSON array of `{op,payload,seq}` frames on stdin, sends each over one
authenticated connection, and prints a JSON array of the server's reply frames
(one per sent frame — the `ack`/`pong`) on stdout. Exit non-zero (with a JSON
`{"error":…}` on stdout) if the upgrade is rejected or the socket dies.

A standalone `--expect-upgrade-status` mode just performs the upgrade and prints
the HTTP status (101 on success, 401 on a bad/missing bearer) — used to assert
auth-at-upgrade parity with the REST 401 without sending any frame.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
from urllib.parse import urlparse

_OPCODE_CONTINUATION = 0x0
_OPCODE_TEXT = 0x1
_OPCODE_CLOSE = 0x8
_OPCODE_PING = 0x9
_OPCODE_PONG = 0xA

_CONTROL_OPCODES = frozenset({_OPCODE_CLOSE, _OPCODE_PING, _OPCODE_PONG})

# Ops the server sends UNSOLICITED — not a reply to any frame we sent. #1849
# pushes `{op:"policy"}` on connect (first-policy-on-connect) and on every
# snapshot change, so it can arrive interleaved with the acks for our frames.
# These are collected and returned to the caller, but they do NOT count toward
# the one-reply-per-sent-frame accounting below (the #1951 fix). Extend this set
# if the transport gains further server→client push ops — and mirror it in the
# matching push-op filter in scripts/e2e-router.sh's ws-parity check.
_PUSH_OPS = frozenset({"policy"})


class UpgradeRejected(Exception):
    def __init__(self, status: int, detail: str = ""):
        super().__init__(f"ws upgrade rejected: HTTP {status} {detail}".strip())
        self.status = status


def _open(url: str, token: str | None, timeout: float):
    u = urlparse(url)
    if u.scheme not in ("ws", "wss"):
        raise ValueError(f"url must be ws:// or wss://, got {url!r}")
    host = u.hostname
    port = u.port or (443 if u.scheme == "wss" else 80)
    path = u.path or "/"
    if u.query:
        path += "?" + u.query

    sock: socket.socket = socket.create_connection((host, port), timeout=timeout)
    if u.scheme == "wss":
        ctx = ssl.create_default_context()
        # Pin a TLS 1.2 floor — create_default_context() otherwise still permits
        # TLSv1/1.1 (CodeQL py/insecure-protocol). Staging terminates modern TLS,
        # so this only forbids the obsolete versions.
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        sock = ctx.wrap_socket(sock, server_hostname=host)

    key = base64.b64encode(os.urandom(16)).decode("ascii")
    lines = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    if token:
        lines.append(f"Authorization: Bearer {token}")
    sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("ascii"))

    # Read the handshake response (headers end at the first blank line).
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("connection closed during ws handshake")
        buf += chunk
    head, _, rest = bytes(buf).partition(b"\r\n\r\n")
    status_line = head.split(b"\r\n", 1)[0].decode("latin1")
    try:
        status = int(status_line.split()[1])
    except (IndexError, ValueError) as e:
        raise RuntimeError(f"malformed ws handshake status line: {status_line!r}") from e
    if status != 101:
        raise UpgradeRejected(status, status_line)
    return sock, bytearray(rest)


def _send_text(sock: socket.socket, text: str) -> None:
    payload = text.encode("utf-8")
    n = len(payload)
    header = bytearray([0x80 | _OPCODE_TEXT])  # FIN + text opcode
    mask_flag = 0x80  # client frames MUST be masked (RFC 6455 §5.3)
    if n < 126:
        header.append(mask_flag | n)
    elif n < 65536:
        header.append(mask_flag | 126)
        header += struct.pack(">H", n)
    else:
        header.append(mask_flag | 127)
        header += struct.pack(">Q", n)
    mask = os.urandom(4)
    header += mask
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(bytes(header) + masked)


def _recv_frame(sock: socket.socket, buf: bytearray) -> tuple[bool, int, bytes]:
    """Read ONE WebSocket frame; return (fin, opcode, payload).

    `fin` is the FIN bit — False marks a non-final fragment of a fragmented
    message (RFC 6455 §5.4). Reassembly across fragments is the job of
    `_recv_message`; this only decodes a single frame off the wire.
    """
    def need(total: int) -> None:
        # buf.extend (in-place) not `buf += chunk`: an augmented assignment would
        # rebind `buf` as a local of this nested function → UnboundLocalError.
        while len(buf) < total:
            chunk = sock.recv(4096)
            if not chunk:
                raise RuntimeError("connection closed mid-frame")
            buf.extend(chunk)

    need(2)
    b0, b1 = buf[0], buf[1]
    fin = bool(b0 & 0x80)
    opcode = b0 & 0x0F
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
    if masked:  # servers don't mask, but handle it for completeness
        need(idx + 4)
        mask = bytes(buf[idx:idx + 4])
        idx += 4
    need(idx + length)
    data = bytes(buf[idx:idx + length])
    if masked:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    del buf[:idx + length]
    return fin, opcode, data


def _recv_message(sock: socket.socket, buf: bytearray) -> tuple[int, bytes]:
    """Read one complete WebSocket message, reassembling fragments (RFC 6455 §5.4).

    Returns (opcode, payload). For a data message the server may split across a
    leading TEXT/BINARY frame (FIN=0) + CONTINUATION (0x0) frames, the last with
    FIN=1 — we concatenate their payloads and return the initial data opcode.
    Without this, a >max-frame-size `policy` push (#1849) read as just its first
    fragment truncates mid-JSON at the fragment boundary (#1958).

    Control frames (ping/pong/close) are never fragmented (RFC 6455 §5.5) and
    are returned as-is when they are not interleaved inside a fragmented message.
    A control frame that arrives BETWEEN data fragments is handled inline (ping/
    pong skipped, close → error) so it does not corrupt the reassembly — its FIN
    bit must NOT be mistaken for the data message's FIN.
    """
    fin, opcode, data = _recv_frame(sock, buf)
    if opcode in _CONTROL_OPCODES:
        return opcode, data
    msg_opcode = opcode
    chunks = [data]
    while not fin:
        frag_fin, frag_opcode, frag_data = _recv_frame(sock, buf)
        if frag_opcode in _CONTROL_OPCODES:
            if frag_opcode == _OPCODE_CLOSE:
                raise RuntimeError("server closed the socket mid-message")
            continue  # interleaved ping/pong — skip WITHOUT advancing `fin`
        if frag_opcode != _OPCODE_CONTINUATION:
            raise RuntimeError(
                f"expected continuation frame (0x0), got opcode {frag_opcode:#x} mid-message"
            )
        chunks.append(frag_data)
        fin = frag_fin
    return msg_opcode, b"".join(chunks)


def _close(sock: socket.socket) -> None:
    try:
        # Masked close frame, no payload.
        mask = os.urandom(4)
        sock.sendall(bytes([0x80 | _OPCODE_CLOSE, 0x80]) + mask)
        sock.close()
    except OSError:
        pass


def send_frames(url: str, token: str | None, frames: list[dict], timeout: float) -> list[dict]:
    """Send every frame, then read replies until each sent frame has a reply.

    Returns the server's TEXT frames in receive order — the solicited reply
    (`ack`/`pong`) for each sent frame PLUS any unsolicited server pushes
    (`_PUSH_OPS`, e.g. #1849's `{op:policy}`) that landed in between. The caller
    matches replies to its sent frames (by op+seq) and ignores the pushes; the
    pushes do NOT gate completion, so an interleaved push can no longer displace
    a frame's reply or leave the last ack unread (#1951).
    """
    sock, buf = _open(url, token, timeout)
    sock.settimeout(timeout)
    # Send all frames up front, then drain replies. Reading is decoupled from
    # sending so a push that arrives before/among the acks is simply collected
    # rather than mistaken for a specific frame's reply.
    replies: list[dict] = []
    solicited_expected = len(frames)
    solicited_seen = 0
    try:
        for frame in frames:
            _send_text(sock, json.dumps(frame))
        while solicited_seen < solicited_expected:
            opcode, data = _recv_message(sock, buf)
            if opcode == _OPCODE_TEXT:
                msg = json.loads(data.decode("utf-8"))
                replies.append(msg)
                op = msg.get("op") if isinstance(msg, dict) else None
                if op not in _PUSH_OPS:
                    # A reply to one of our frames (ack / pong) — counts toward done.
                    solicited_seen += 1
                continue
            if opcode == _OPCODE_PING:
                continue  # benign; server-initiated control ping
            if opcode == _OPCODE_CLOSE:
                raise RuntimeError("server closed the socket before replying")
            # ignore pong (continuation frames are reassembled inside _recv_message)
    finally:
        _close(sock)
    return replies


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="ws:// or wss:// URL of /api/router/ws")
    ap.add_argument("--token", default=None, help="router bearer token (omit to test the 401 path)")
    ap.add_argument("--timeout", type=float, default=15.0)
    ap.add_argument(
        "--expect-upgrade-status",
        action="store_true",
        help="just attempt the upgrade and print the HTTP status (101/401)",
    )
    args = ap.parse_args()

    if args.expect_upgrade_status:
        try:
            sock, _ = _open(args.url, args.token, args.timeout)
            _close(sock)
            print(json.dumps({"status": 101}))
            return 0
        except UpgradeRejected as e:
            print(json.dumps({"status": e.status}))
            return 0

    try:
        frames = json.load(sys.stdin)
        if not isinstance(frames, list):
            raise ValueError("stdin must be a JSON array of frames")
        replies = send_frames(args.url, args.token, frames, args.timeout)
        print(json.dumps(replies))
        return 0
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": str(e)}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
