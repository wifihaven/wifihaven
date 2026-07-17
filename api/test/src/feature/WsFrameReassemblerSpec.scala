package wifihaven.api.feature

import wifihaven.api.routes.WsFrameReassembler
import wifihaven.api.routes.WsFrameReassembler.Outcome
import zio.Chunk
import zio.http.WebSocketFrame
import zio.test.*

import java.nio.charset.StandardCharsets

/**
 * #2268: pure unit tests for the server-side WS frame reassembler — the mirror of the router-side
 * reassembler (#1959) the server was missing. Render's edge fragments large frames at ~4 KiB, so a
 * message arrives as `Text(isFinal=false)` + `Continuation…`; the reassembler must rebuild the
 * whole message (byte-accurately across continuation boundaries) and only then emit it. `step` is a
 * pure fold, so these drive it directly with no server.
 */
object WsFrameReassemblerSpec extends ZIOSpecDefault {

  private def bytes(s: String): Chunk[Byte] =
    Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8))

  /** Fold frames through `step`, returning the (finalState, orderedOutcomes). */
  private def run(frames: List[WebSocketFrame]): (Chunk[Byte], List[Outcome]) =
    frames.foldLeft((WsFrameReassembler.empty, List.empty[Outcome])) { case ((st, outs), f) =>
      val (st2, out) = WsFrameReassembler.step(st, f)
      (st2, outs :+ out)
    }

  private def messages(frames: List[WebSocketFrame]): List[String] =
    run(frames)._2.collect { case Outcome.Message(t, _) => t }

  def spec = suite("WsFrameReassembler (#2268)")(
    test("an unfragmented final Text is the whole message (fast path, no buffering)") {
      val (state, outs) = run(List(WebSocketFrame.Text("""{"op":"ping"}""")))
      assertTrue(
        outs == List(Outcome.Message("""{"op":"ping"}""", fragmented = false)),
        state == WsFrameReassembler.empty,
      )
    },
    test(
      "a message split across Text(non-final) + Continuation(final) reassembles into ONE message",
    ) {
      val frames        = List(
        WebSocketFrame.Text("""{"op":"usa""", isFinal = false),
        WebSocketFrame.Continuation(bytes("""ge","payload":{}}"""), isFinal = true),
      )
      val (state, outs) = run(frames)
      assertTrue(
        // The lead fragment buffers (Incomplete), the final continuation emits the whole message
        // flagged fragmented=true (so the caller meters the reassembly).
        outs == List(
          Outcome.Incomplete,
          Outcome.Message("""{"op":"usage","payload":{}}""", fragmented = true),
        ),
        state == WsFrameReassembler.empty,
      )
    },
    test("a message split across MANY continuation frames reassembles in order") {
      val frames = List(
        WebSocketFrame.Text("a", isFinal = false),
        WebSocketFrame.Continuation(bytes("b"), isFinal = false),
        WebSocketFrame.Continuation(bytes("c"), isFinal = false),
        WebSocketFrame.Continuation(bytes("d"), isFinal = true),
      )
      assertTrue(messages(frames) == List("abcd"))
    },
    test("a multi-byte UTF-8 char split across a continuation boundary decodes correctly") {
      // "é" is 0xC3 0xA9 in UTF-8; split the two bytes across the boundary. Byte-level reassembly
      // (concatenate raw bytes, decode once) must recover it — a per-fragment decode would not.
      val eBytes = "é".getBytes(StandardCharsets.UTF_8)
      val frames = List(
        WebSocketFrame.Text("x", isFinal = false),
        WebSocketFrame.Continuation(Chunk(eBytes(0)), isFinal = false),
        WebSocketFrame.Continuation(Chunk(eBytes(1)), isFinal = true),
      )
      assertTrue(messages(frames) == List("xé"))
    },
    test("a control frame interleaved between fragments does NOT corrupt reassembly") {
      // RFC 6455 §5.4: control frames may be injected between the fragments of a message.
      val frames    = List(
        WebSocketFrame.Text("hel", isFinal = false),
        WebSocketFrame.Ping,
        WebSocketFrame.Continuation(bytes("lo"), isFinal = true),
      )
      val (_, outs) = run(frames)
      assertTrue(
        outs == List(
          Outcome.Incomplete,
          Outcome.Passthrough,
          Outcome.Message("hello", fragmented = true),
        ),
      )
    },
    test("a new Text while a partial is buffered starts fresh (a data frame can't interleave)") {
      val frames = List(
        WebSocketFrame.Text("stale-partial", isFinal = false),
        WebSocketFrame.Text("""{"op":"events"}"""), // final → whole message, partial discarded
      )
      assertTrue(messages(frames) == List("""{"op":"events"}"""))
    },
    test("reassembly exceeding the cap yields Overflow and resets the buffer") {
      val huge          = "x" * (WsFrameReassembler.MaxMessageBytes + 1)
      val frames        = List(
        WebSocketFrame.Text("start", isFinal = false),
        WebSocketFrame.Continuation(bytes(huge), isFinal = false),
      )
      val (state, outs) = run(frames)
      assertTrue(
        outs.head == Outcome.Incomplete,
        outs(1) match { case Outcome.Overflow(_) => true; case _ => false },
        state == WsFrameReassembler.empty, // reset so the connection can be closed cleanly
      )
    },
    test("a Ping/Pong on an empty buffer is Passthrough and leaves state empty") {
      val (state, outs) = run(List(WebSocketFrame.Ping, WebSocketFrame.Pong))
      assertTrue(
        outs == List(Outcome.Passthrough, Outcome.Passthrough),
        state == WsFrameReassembler.empty,
      )
    },
  )
}
