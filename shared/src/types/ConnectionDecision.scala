package familydns.shared.types

import zio.json.*

/** Per-flow decision the API emits on the fallback `POST /api/router/decision` endpoint. */
enum ConnectionDecision {
  case Allow, Block
}

object ConnectionDecision {
  def asString(d: ConnectionDecision): String      = d match {
    case Allow => "allow"
    case Block => "block"
  }
  def parse(s: String): Option[ConnectionDecision] = s.toLowerCase match {
    case "allow" => Some(Allow)
    case "block" => Some(Block)
    case _       => None
  }
  given JsonCodec[ConnectionDecision]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown decision: $s"),
    asString,
  )
}
