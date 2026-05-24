package wifihaven.api.db

import zio.json.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

// #862 — opaque cursors for infinite-scroll endpoints. JSON payload, base64url
// encoded so it round-trips through query strings without escaping. The SPA
// treats the string as opaque.
object Cursor {

  // Raw connection_events: keyset on (ts DESC, id DESC).
  case class LogCursor(ts: Instant, id: Long) derives JsonCodec

  // Raw traffic_reports: keyset on (period_start DESC, mac, host).
  case class RawTrafficCursor(ts: Instant, mac: String, host: String) derives JsonCodec

  // Aggregated series (traffic_reports and connection_events): keyset on
  // (window_start DESC, group_key ASC). `key` is the comma-joined group values
  // in the request's groupBy column order — stable per query, opaque to caller.
  case class AggCursor(ws: String, key: String) derives JsonCodec

  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder

  def encode[A: JsonEncoder](a: A): String =
    enc.encodeToString(a.toJson.getBytes(StandardCharsets.UTF_8))

  def decode[A: JsonDecoder](s: String): Either[String, A] =
    try {
      val bytes = dec.decode(s)
      new String(bytes, StandardCharsets.UTF_8).fromJson[A]
    } catch {
      case _: IllegalArgumentException => Left(s"invalid cursor: $s")
    }
}
