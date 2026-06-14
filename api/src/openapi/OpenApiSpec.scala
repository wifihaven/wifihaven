package wifihaven.api.openapi

import zio.Chunk
import zio.http.*
import zio.json.ast.Json

/**
 * #638 — generate a minimal OpenAPI 3.0.3 spec from the live `Routes` tree.
 *
 * The route table is the single source of truth (per AGENTS.md §single-source-of-truth): the
 * generator walks `Routes` at boot, extracts `(method, path)` from each route's `RoutePattern`, and
 * emits an envelope with one operation per (path, method). Adding a new route automatically appears
 * in the spec; deleting one automatically disappears. There is no checked-in YAML to drift.
 *
 * What this DOES cover today:
 *   - HTTP method + path template (with `{param}` placeholders matching OpenAPI's path-parameter
 *     syntax)
 *   - Public vs. authenticated partition: paths under [[OpenApiSpec.publicPathPrefixes]] are
 *     advertised as unauthenticated; everything else carries a `bearerAuth` security requirement
 *   - The bearer-JWT security scheme declaration
 *
 * What this does NOT cover (deliberate — keeps the surface honest and avoids a hand-maintained
 * schema catalog that would drift the moment a Scala case class changes):
 *   - Request/response body schemas
 *   - Per-route summaries and descriptions
 *   - Per-operation tagging by feature area
 *
 * Filling those in is incremental follow-up work (a tagged `OperationMeta` registry keyed by
 * `(method, path)`); the right place is alongside each route definition so the request/response
 * codec sits next to the schema description, matching the locality the AGENTS.md
 * single-source-of-truth rule wants.
 */
object OpenApiSpec {

  /**
   * Paths the API exposes without bearer-token auth. Kept as a small explicit list rather than
   * introspecting handlers (which is opaque after `Routes` composes them through middleware):
   * authentication is already a per-route convention enforced by `requireAuth` / `requireAdmin` at
   * the top of each handler, so the spec just mirrors that convention.
   *
   * If a new public route ships, add its prefix here so the spec doesn't falsely advertise it as
   * bearer-required.
   */
  private val publicPathPrefixes: Vector[String] =
    Vector(
      "/api/health",
      "/api/version",
      "/api/auth/login",
      "/api/blocked",         // #335: unauthenticated block-page support
      "/api/openapi.json",
      "/api/docs",
      "/api/metrics",
      "/api/router/register", // one-time enrollment
      "/api/router/policy",   // router bearer (separate auth scheme, not user JWT)
      "/api/router/events",
      "/api/router/usage",
      "/api/router/metrics",
      "/api/blocklists",      // router-served blocklist URLs
    )

  private def isPublic(path: String): Boolean =
    publicPathPrefixes.exists(p =>
      path == p || path.startsWith(p + "/") || path.startsWith(p + "?"),
    )

  /**
   * Generate the OpenAPI 3.0.3 document. `version` is interpolated into `info.version` so the
   * served spec carries the API build identity (the same SHA `/api/version` reports).
   *
   * Multiple `Routes` values can be passed — they are merged into one paths object, so the caller
   * can hand in the same chunks that `Main` assembles (system / stats / router / SPA) without
   * flattening first.
   */
  def generate(version: String, routesList: Routes[?, ?]*): Json = {
    // (path, method-lowercase) -> Json operation
    val byPath = scala.collection.mutable.LinkedHashMap.empty[
      String,
      scala.collection.mutable.LinkedHashMap[String, Json],
    ]

    for {
      rs <- routesList
      r  <- rs.routes
    } {
      val rp     = r.routePattern
      val method = rp.method
      // Skip Method.ANY (would land as "GET" with no real semantics) and
      // CUSTOM methods we don't enumerate.
      if (method != Method.ANY) {
        val path = rp.pathCodec.render
        val mKey = method.name.toLowerCase
        val ops  = byPath.getOrElseUpdate(path, scala.collection.mutable.LinkedHashMap.empty)
        // First-wins: a route added earlier in the assembly takes precedence,
        // matching `Routes.++`'s left-bias.
        val _    = ops.getOrElseUpdate(mKey, operationFor(path, method))
      }
    }

    val pathsObj = Json.Obj(
      Chunk.fromIterable(
        byPath.toSeq.sortBy(_._1).map { case (p, ops) =>
          p -> Json.Obj(Chunk.fromIterable(ops.toSeq))
        },
      ),
    )

    Json.Obj(
      Chunk(
        "openapi"    -> Json.Str("3.0.3"),
        "info"       -> Json.Obj(
          Chunk(
            "title"       -> Json.Str("WifiHaven API"),
            "version"     -> Json.Str(version),
            "description" -> Json.Str(
              "Auto-generated from the live zio-http route table (issue #638). " +
                "Paths and methods are authoritative; request/response schemas are " +
                "added incrementally per-route — absence here does NOT mean a route " +
                "is undocumented in code.",
            ),
          ),
        ),
        "components" -> Json.Obj(
          Chunk(
            "securitySchemes" -> Json.Obj(
              Chunk(
                "bearerAuth" -> Json.Obj(
                  Chunk(
                    "type"         -> Json.Str("http"),
                    "scheme"       -> Json.Str("bearer"),
                    "bearerFormat" -> Json.Str("JWT"),
                    "description"  -> Json.Str(
                      "User-session JWT issued by POST /api/auth/login. " +
                        "Router-side endpoints use a separate enrollment-derived bearer " +
                        "managed by the OpenWRT agent.",
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
        "paths"      -> pathsObj,
      ),
    )
  }

  private def operationFor(path: String, method: Method): Json = {
    val pathParams                              = extractPathParams(path)
    val parametersField: Option[(String, Json)] =
      if (pathParams.isEmpty) None
      else
        Some(
          "parameters" -> Json.Arr(
            Chunk.fromIterable(pathParams.map { name =>
              Json.Obj(
                Chunk(
                  "name"     -> Json.Str(name),
                  "in"       -> Json.Str("path"),
                  "required" -> Json.Bool(true),
                  "schema"   -> Json.Obj(Chunk("type" -> Json.Str("string"))),
                ),
              )
            }),
          ),
        )

    val base = Chunk(
      "summary"   -> Json.Str(s"${method.name} $path"),
      "responses" -> Json.Obj(
        Chunk(
          "default" -> Json.Obj(
            Chunk("description" -> Json.Str("Response (schema TBD — see #638)")),
          ),
        ),
      ),
    )

    val withParams = parametersField.fold(base)(p => base :+ p)

    val withSec =
      if (isPublic(path)) withParams
      else
        withParams :+ ("security" -> Json.Arr(
          Chunk(Json.Obj(Chunk("bearerAuth" -> Json.Arr(Chunk.empty)))),
        ))

    Json.Obj(withSec)
  }

  /** Pull out `{name}` placeholders from the rendered path. */
  private def extractPathParams(path: String): Vector[String] = {
    val rx = """\{([^}]+)\}""".r
    rx.findAllMatchIn(path).map(_.group(1)).toVector
  }
}
