package wifihaven.api.routes

import wifihaven.api.BuildInfo
import wifihaven.shared.WireSchema
import zio.*
import zio.http.*

/**
 * Unauthenticated discovery endpoint (#597).
 *
 * Returns the API build identifier and the integer wire-schema version. The router agent calls
 * this once at startup and compares the wireSchema against its own baked-in value, logging a
 * loud warning on mismatch. Full capability negotiation is tracked under #376; the unified
 * deploy gate that coordinates atomic API + agent bumps is tracked under #498.
 *
 * The endpoint itself is intentionally not versioned (chicken-and-egg).
 */
object VersionRoutes {
  def routes: Routes[Any, Response] = {
    val body = s"""{"apiBuild":"${BuildInfo.sha}","wireSchema":${WireSchema.Current}}"""
    Routes(
      Method.GET / "api" / "version" ->
        handler { (_: Request) => Response.json(body) },
    )
  }
}
