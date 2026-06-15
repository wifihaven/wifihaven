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
 *   - Three-way auth partition: paths are classified [[AuthScheme.Public]] / [[AuthScheme.User]] /
 *     [[AuthScheme.Router]] and tagged with the matching `securityScheme` (`bearerAuth` for user
 *     JWT, `routerBearer` for the OpenWRT agent's enrollment-derived token, or nothing for public
 *     probes). Pinned against the production handlers by `OpenApiAuthPartitionSpec`.
 *   - The `bearerAuth` (user JWT) and `routerBearer` (router) security scheme declarations
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
   * Which auth scheme applies to a given path prefix. Three states:
   *   - [[AuthScheme.Public]] — fully unauthenticated (block page, login, health, version, …)
   *   - [[AuthScheme.User]] — user-session bearer JWT issued by `POST /api/auth/login`
   *   - [[AuthScheme.Router]] — enrollment-derived bearer carried by the OpenWRT agent
   *
   * The list mirrors the per-handler `requireAuth` / `requireAdmin` / `routerAuth` convention. It
   * is hand-maintained on purpose (introspecting handlers after `Routes` composes them through
   * middleware is opaque), and pinned against the production route table by
   * `OpenApiAuthPartitionSpec` — any new public/router/user flip must update either the list or the
   * test, surfacing the §single-source-of-truth display-vs-enforcement drift before merge.
   *
   * Prefixes are matched longest-first (so `/api/router/register` resolves before the broader
   * `/api/router` entry).
   */
  enum AuthScheme {
    case Public, User, Router
  }

  /**
   * (prefix, scheme) — first match wins. Ordering is longest-prefix-first so a more specific
   * router-bearer carve-out beats a broader public prefix (e.g. `/api/router/register` is router-
   * authed, `/api/blocked` is open).
   */
  private val authPartition: Vector[(String, AuthScheme)] = {
    val raw = Vector(
      // Fully public — discovery / probes / login / kid-side block page
      "/api/health"          -> AuthScheme.Public,
      "/api/version"         -> AuthScheme.Public,
      "/api/auth/login"      -> AuthScheme.Public,
      "/api/blocked"         -> AuthScheme.Public,
      "/api/openapi.json"    -> AuthScheme.Public,
      "/api/docs"            -> AuthScheme.Public,
      // Router bearer — the OpenWRT/OPNsense agent's enrollment-derived token, NOT the user JWT
      "/api/router/policy"   -> AuthScheme.Router,
      "/api/router/register" -> AuthScheme.Router,
      "/api/router/events"   -> AuthScheme.Router,
      "/api/router/usage"    -> AuthScheme.Router,
      "/api/router/metrics"  -> AuthScheme.Router,
      "/api/router/decision" -> AuthScheme.Router,
      "/api/blocklists" -> AuthScheme.Router, // router-served blocklist URLs (`/api/blocklists/{id}`)
    )
    // Longest prefix first so specific carve-outs win.
    raw.sortBy { case (p, _) => -p.length }
  }

  private def authFor(path: String): AuthScheme =
    authPartition
      .collectFirst {
        case (p, scheme) if path == p || path.startsWith(p + "/") => scheme
      }
      .getOrElse(AuthScheme.User)

  /** Exposed for the auth-partition drift test in `api/test`. */
  def authForTesting(path: String): AuthScheme = authFor(path)

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
                "bearerAuth"   -> Json.Obj(
                  Chunk(
                    "type"         -> Json.Str("http"),
                    "scheme"       -> Json.Str("bearer"),
                    "bearerFormat" -> Json.Str("JWT"),
                    "description"  -> Json.Str(
                      "User-session JWT issued by POST /api/auth/login. Carried by SPA + admin " +
                        "clients on every authenticated /api/* call.",
                    ),
                  ),
                ),
                "routerBearer" -> Json.Obj(
                  Chunk(
                    "type"        -> Json.Str("http"),
                    "scheme"      -> Json.Str("bearer"),
                    "description" -> Json.Str(
                      "Enrollment-derived bearer issued by POST /api/router/register and carried " +
                        "by the OpenWRT/OPNsense agent on /api/router/* and /api/blocklists/* " +
                        "calls. Distinct from the user-session JWT.",
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

    val withSec = authFor(path) match {
      case AuthScheme.Public => withParams
      case AuthScheme.User   =>
        withParams :+ ("security" -> Json.Arr(
          Chunk(Json.Obj(Chunk("bearerAuth" -> Json.Arr(Chunk.empty)))),
        ))
      case AuthScheme.Router =>
        withParams :+ ("security" -> Json.Arr(
          Chunk(Json.Obj(Chunk("routerBearer" -> Json.Arr(Chunk.empty)))),
        ))
    }

    Json.Obj(withSec)
  }

  /** Pull out `{name}` placeholders from the rendered path. */
  private def extractPathParams(path: String): Vector[String] = {
    val rx = """\{([^}]+)\}""".r
    rx.findAllMatchIn(path).map(_.group(1)).toVector
  }
}
