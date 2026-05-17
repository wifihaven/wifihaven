package wifihaven.shared.types

import zio.json.*

/**
 * Opaque-string wrappers around tokens / hashes / URLs. Each validates the format we emit, so a
 * malformed value at a boundary is caught at parse time rather than echoed downstream.
 */

opaque type ETag             = String
opaque type EnrollmentToken  = String
opaque type RouterToken      = String
opaque type JwtToken         = String
opaque type BlocklistVersion = String
opaque type Url              = String
opaque type BlocklistUrl     = String
opaque type Sha256Hex        = String

object ETag {
  private val Pattern                          = "^\"[A-Za-z0-9._:+/=-]+\"$".r
  def parse(raw: String): Either[String, ETag] =
    if Pattern.matches(raw) then Right(raw) else Left(s"invalid ETag: $raw")
  def unsafe(s: String): ETag                  = s
  extension (e: ETag) def value: String        = e
  given JsonCodec[ETag]                        = JsonCodec.string.transformOrFail(parse, _.value)
}

object EnrollmentToken {
  private val Pattern                                     = "^et_[A-Za-z0-9_-]{32}$".r
  def parse(raw: String): Either[String, EnrollmentToken] =
    if Pattern.matches(raw) then Right(raw) else Left(s"invalid enrollment token")
  def unsafe(s: String): EnrollmentToken                  = s
  extension (t: EnrollmentToken) def value: String        = t
  given JsonCodec[EnrollmentToken] = JsonCodec.string.transformOrFail(parse, _.value)
}

object RouterToken {
  private val Pattern                                 = "^rt_[A-Za-z0-9_-]{43}$".r
  def parse(raw: String): Either[String, RouterToken] =
    if Pattern.matches(raw) then Right(raw) else Left(s"invalid router token")
  def unsafe(s: String): RouterToken                  = s
  extension (t: RouterToken) def value: String        = t
  given JsonCodec[RouterToken] = JsonCodec.string.transformOrFail(parse, _.value)
}

object JwtToken {
  private val Pattern = "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$".r
  def parse(raw: String): Either[String, JwtToken] =
    if Pattern.matches(raw) then Right(raw) else Left("invalid JWT format")
  def unsafe(s: String): JwtToken                  = s
  extension (t: JwtToken) def value: String        = t
  given JsonCodec[JwtToken] = JsonCodec.string.transformOrFail(parse, _.value)
}

object BlocklistVersion {
  private val Pattern = "^[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}$".r
  def parse(raw: String): Either[String, BlocklistVersion] =
    if Pattern.matches(raw) then Right(raw) else Left(s"invalid blocklist version: $raw")
  def unsafe(s: String): BlocklistVersion                  = s
  extension (v: BlocklistVersion) def value: String        = v
  given JsonCodec[BlocklistVersion] = JsonCodec.string.transformOrFail(parse, _.value)
}

object Url {
  def parse(raw: String): Either[String, Url] =
    if raw.isEmpty then Left("empty URL")
    else
      scala.util.Try(java.net.URI.create(raw)).toEither.left.map(_.getMessage).flatMap { u =>
        val scheme = Option(u.getScheme).map(_.toLowerCase)
        val host   = Option(u.getHost).filter(_.nonEmpty)
        if !scheme.exists(s => s == "http" || s == "https") then
          Left(s"URL must be http or https: $raw")
        else if host.isEmpty then Left(s"URL missing host: $raw")
        else Right(raw)
      }
  def unsafe(s: String): Url                  = s
  extension (u: Url) def value: String        = u
  given JsonCodec[Url]                        = JsonCodec.string.transformOrFail(parse, _.value)
}

object BlocklistUrl {
  // Permissive: accepts either an absolute http(s) URL or a relative path served by this API.
  // No whitespace / control chars; bounded length.
  private val Pattern                                  = "^[^\\s\\x00-\\x1f]{1,2048}$".r
  def parse(raw: String): Either[String, BlocklistUrl] =
    if Pattern.matches(raw) then Right(raw) else Left(s"invalid blocklist URL: $raw")
  def unsafe(s: String): BlocklistUrl                  = s
  extension (u: BlocklistUrl) def value: String        = u
  given JsonCodec[BlocklistUrl] = JsonCodec.string.transformOrFail(parse, _.value)
}

object Sha256Hex {
  private val Pattern                               = "^[0-9a-f]{64}$".r
  def parse(raw: String): Either[String, Sha256Hex] =
    if Pattern.matches(raw) then Right(raw) else Left("invalid sha256 hex")
  def unsafe(s: String): Sha256Hex                  = s
  extension (h: Sha256Hex) def value: String        = h
  given JsonCodec[Sha256Hex] = JsonCodec.string.transformOrFail(parse, _.value)
}
