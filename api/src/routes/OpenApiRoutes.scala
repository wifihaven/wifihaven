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
 * assets into the JVM would balloon the deploy for no real benefit (any environment that can reach
 * the API can reach jsdelivr). If that ever becomes a problem — e.g. the self-hosted install on a
 * network without outbound HTTPS — swap to a vendored copy under `api/resources/swagger-ui/`.
 */
object OpenApiRoutes {

  /**
   * @param version
   *   same SHA `/api/version` reports — interpolated into `info.version`
   * @param apiRoutesList
   *   every `Routes` chunk the API server mounts (system, stats, router, …)
   */
  def routes(version: String, apiRoutesList: Routes[?, ?]*): Routes[Any, Response] = {
    // Generate once at construction time; the route table is fixed for the
    // lifetime of the JVM, so caching the rendered JSON avoids re-walking
    // the routes on every probe and any allocation in the hot path.
    val specJson: String = OpenApiSpec.generate(version, apiRoutesList*).toString

    Routes(
      Method.GET / "api" / "openapi.json" ->
        handler { (_: Request) => Response.json(specJson) },
      Method.GET / "api" / "docs"         ->
        handler { (_: Request) =>
          Response
            .html(swaggerUiHtml)
        },
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
