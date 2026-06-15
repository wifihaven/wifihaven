package wifihaven.api.routes

import wifihaven.api.openapi.OpenApiSpec
import zio.*
import zio.http.*

/**
 * #638 — serve the auto-generated OpenAPI spec and Swagger UI.
 *
 * Both routes are unauthenticated by design: they are the discovery surface for operators, the
 * OpenWRT agent maintainers, and future integrations. The spec itself describes which routes
 * require bearer auth via the `bearerAuth` security scheme, so leaving /api/openapi.json open does
 * not weaken anything — it just lists what's behind auth.
 *
 * Swagger UI is loaded from the jsdelivr CDN. The HTML payload is a few lines; bundling the UI
 * assets into the JVM would balloon the deploy for no real benefit on cloud staging/prod (where
 * outbound HTTPS to jsdelivr is reliable). Self-hosted installs on locked-down networks need the
 * vendored alternative — tracked under #1749.
 */
object OpenApiRoutes {

  /**
   * @param version
   *   same SHA `/api/version` reports — interpolated into `info.version`
   * @param apiRoutesList
   *   every `Routes` chunk the API server mounts (system, stats, router, …)
   */
  def routes(version: String, apiRoutesList: Routes[?, ?]*): Routes[Any, Response] = {
    // Self-list: the spec advertises the discovery surface itself. Build a
    // dummy `Routes` containing just the two patterns so the generator walks
    // them alongside the caller's chunks; the actual handlers are constructed
    // below and share these patterns.
    val selfRoutes: Routes[Any, Response] =
      Routes(
        Method.GET / "api" / "openapi.json" -> handler((_: Request) => Response.ok),
        Method.GET / "api" / "docs"         -> handler((_: Request) => Response.ok),
      )

    // Generate once at construction time; the route table is fixed for the
    // lifetime of the JVM, so caching the rendered JSON avoids re-walking the
    // routes on every probe and any allocation in the hot path.
    val specJson: String =
      OpenApiSpec.generate(version, (apiRoutesList :+ selfRoutes)*).toString

    Routes(
      Method.GET / "api" / "openapi.json" ->
        handler { (_: Request) => Response.json(specJson) },
      Method.GET / "api" / "docs"         ->
        handler { (_: Request) => Response.html(swaggerUiHtml) },
    )
  }

  // Minimal Swagger UI shell — points at the served spec, no auth, no
  // bundled assets. The CDN URLs are pinned to a specific Swagger UI
  // release so an upstream change can't silently alter what operators see.
  private val swaggerUiHtml: String =
    """<!DOCTYPE html>
      |<html lang="en">
      |  <head>
      |    <meta charset="utf-8" />
      |    <meta name="viewport" content="width=device-width, initial-scale=1" />
      |    <title>WifiHaven API — Swagger UI</title>
      |    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css" />
      |  </head>
      |  <body>
      |    <div id="swagger-ui"></div>
      |    <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js" charset="UTF-8"></script>
      |    <script>
      |      window.onload = function () {
      |        window.ui = SwaggerUIBundle({
      |          url: "/api/openapi.json",
      |          dom_id: "#swagger-ui",
      |          deepLinking: true,
      |        });
      |      };
      |    </script>
      |  </body>
      |</html>
      |""".stripMargin
}
