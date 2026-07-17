package wifihaven.api.routes

import zio.Chunk
import zio.http.WebSocketFrame

import java.nio.charset.StandardCharsets

/**
 * Server-side reassembly of fragmented inbound WebSocket messages.
 *
 * WHY: a large text message a peer sends as one logical frame can be fragmented at the WebSocket
 * protocol level by an intermediary — Render's edge re-fragments large frames at ~4 KiB (#1959) —
 * arriving as a lead `Text(isFinal=false)` followed by `Continuation` frames until one with
 * `isFinal=true`. zio-http 3.0.1 surfaces these RAW fragments (there is no
 * `WebSocketFrameAggregator` seam in `WebSocketConfig`/`SocketDecoder`, and no Netty-pipeline
 * hook), so a handler that decodes each `Text` immediately and drops `Continuation` frames sees
 * only the truncated first fragment ("Unexpected end of input") and loses the whole message.
 *
 * This is the MIRROR of the router-side reassembler (#1959): the agent already reassembles frames
 * Render fragments on the server→router path, but the server never got the symmetric half for the
 * router→server path — which wedged the router→API usage-report transport (traffic_reports ingest
 * stalled; #2268). Both [[RouterWsRoutes]] and [[SpaWsRoutes]] share this one reassembler so the
 * logic lives in exactly one place.
 *
 * PURE fold `(state, frame) => (state, Outcome)` — no I/O — so it unit-tests on the dev host
 * exactly as it runs, mirroring the router's pure `ws_frame` reassembler. Callers hold a
 * per-connection `Ref[Chunk[Byte]]` (start [[empty]]) and act on the returned [[Outcome]].
 *
 * Reassembly is BYTE-level: each `Continuation`'s raw bytes are concatenated and the full buffer is
 * UTF-8-decoded ONCE at the end, so a multi-byte character split across a continuation boundary
 * round-trips correctly. (Residual edge: zio-http eagerly UTF-8-decodes the LEAD `Text` fragment,
 * so a multi-byte character split at the very first ~4 KiB boundary is already lossy before we see
 * it — a cosmetic one-character corruption in one report, never a transport failure or a decode
 * error, because all structural JSON is ASCII.)
 */
object WsFrameReassembler {

  /** Max reassembled message size — mirrors the router-side 1 MiB cap (#1959). */
  val MaxMessageBytes: Int = 1024 * 1024

  /** The starting (and post-message / post-overflow) reassembly state. */
  val empty: Chunk[Byte] = Chunk.empty

  sealed trait Outcome
  object Outcome {

    /** A complete text message is ready to dispatch. */
    final case class Message(text: String) extends Outcome

    /** A non-final fragment was buffered; wait for more frames. */
    case object Incomplete extends Outcome

    /**
     * Not a data frame this reassembler owns (`Ping`/`Pong`/`Close`/`Binary`). The caller handles
     * it as before; the reassembly buffer is left untouched because control frames MAY be
     * interleaved between the fragments of a message (RFC 6455 §5.4).
     */
    case object Passthrough extends Outcome

    /**
     * Reassembly exceeded [[MaxMessageBytes]]; the caller must close the connection (a peer
     * streaming an unbounded message is a memory-exhaustion hazard). The buffer is reset to
     * [[empty]].
     */
    final case class Overflow(bytes: Int) extends Outcome
  }

  /**
   * Fold one inbound frame into the reassembly state.
   *
   *   - `Text(isFinal=true)` → the whole message (fast path — no byte copy; also resets any stray
   *     partial, since a data frame cannot interleave a fragmented message, RFC 6455 §5.4).
   *   - `Text(isFinal=false)` → start a fresh byte buffer.
   *   - `Continuation(isFinal=false)` → append; `Continuation(isFinal=true)` → append + emit.
   *   - anything else → [[Outcome.Passthrough]], buffer untouched.
   */
  def step(state: Chunk[Byte], frame: WebSocketFrame): (Chunk[Byte], Outcome) =
    frame match {
      case t: WebSocketFrame.Text         =>
        if (t.isFinal) (empty, Outcome.Message(t.text))
        else capped(Chunk.fromArray(t.text.getBytes(StandardCharsets.UTF_8)))
      case c: WebSocketFrame.Continuation =>
        val buf = state ++ c.buffer
        if (c.isFinal) (empty, Outcome.Message(new String(buf.toArray, StandardCharsets.UTF_8)))
        else capped(buf)
      case _                              =>
        (state, Outcome.Passthrough)
    }

  private def capped(buf: Chunk[Byte]): (Chunk[Byte], Outcome) =
    if (buf.length > MaxMessageBytes) (empty, Outcome.Overflow(buf.length))
    else (buf, Outcome.Incomplete)
}
